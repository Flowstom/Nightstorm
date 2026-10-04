package net.flowstom.nightstorm;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuralMigrationTest {
    @TempDir Path root;
    private static final String TAGS="unrelated/Catalog", UNION="unrelated/Choice", FIRST="unrelated/One", SECOND="unrelated/Many";
    private static final Handle BOOTSTRAP=new Handle(Opcodes.H_INVOKESTATIC,"fixture/Bootstrap","create","()V",false);

    @Test void discoversEveryVariantAndMigratesUnrelatedApiWithoutDuplicatingRemovedValues() throws Exception {
        var classes=catalog();
        var discovered=WireDispatch.scan(classes.get(UNION),classes::get).orElseThrow();
        assertEquals(List.of(17,42),discovered.variants().stream().map(WireDispatch.Variant::id).toList());
        assertEquals(3,discovered.variants().get(1).payload().fields().size());
        var oldRoot=WireFixtures.node("unrelated/Envelope",List.of("key:I","bundle:Lunrelated/Bundle;","active:Z"),
                List.of("VAR_INT","unrelated/Bundle#STREAM_CODEC","BOOL"),List.of(0,1,2),true);
        var oldBundle=WireFixtures.node("unrelated/Bundle",List.of("route:Ljava/lang/String;","count:I","trace:J","obsolete:D"),
                List.of("STRING_UTF8","VAR_INT","LONG","DOUBLE"),List.of(0,1,2,3),true);
        var target=WireFixtures.node(oldRoot.name,List.of("key:I","route:L"+UNION+";","count:I","trace:J","active:Z"),
                List.of("VAR_INT",UNION+"#STREAM_CODEC","VAR_INT","LONG","BOOL"),List.of(0,1,2,3,4),true);
        var plan=WireDispatch.plan(oldRoot,target,name->name.equals(oldBundle.name)?oldBundle:null,classes::get).orElseThrow();
        assertEquals(1,plan.legacySource());
        assertEquals(List.of(0,-1,2,3,5),plan.sources());
        write("Envelope.java","""
                package example;
                record Envelope(int key, String route, int count, long trace, double obsolete, boolean active) {
                  static final NetworkBuffer.Type<Envelope> SERIALIZER=NetworkBufferTemplate.template(
                    NetworkBuffer.VAR_INT,Envelope::key,NetworkBuffer.STRING,Envelope::route,
                    NetworkBuffer.VAR_INT,Envelope::count,NetworkBuffer.LONG,Envelope::trace,
                    NetworkBuffer.DOUBLE,Envelope::obsolete,NetworkBuffer.BOOLEAN,Envelope::active,Envelope::new);
                }
                """);
        var migration=PacketMigrationScanner.Migration.dispatch(List.of(),plan).withPacket(packet("Envelope"));
        RetainedPacketMigrator.apply(root,List.of(migration));
        String once=Files.readString(source("Envelope.java"));
        RetainedPacketMigrator.apply(root,List.of(migration));assertEquals(once,Files.readString(source("Envelope.java")));
        write("Check.java","""
                package example;
                public class Check { public static void verify() {
                  var old=new Envelope(5,"hello",8,99L,4.5d,true);
                  var expected=new Envelope(5,new Envelope.WireChoiceCase0("hello"),8,99L,true);
                  if(!old.equals(expected))throw new AssertionError(old);
                  var many=new Envelope(7,new Envelope.WireChoiceCase1("data",11,123L),9,456L,false);
                  for(var value:java.util.List.of(expected,many)) {
                    var buffer=new NetworkBuffer();Envelope.SERIALIZER.write(buffer,value);
                    if(!Envelope.SERIALIZER.read(buffer).equals(value)||buffer.index!=buffer.values.size())throw new AssertionError(buffer.values);
                  }
                  var invalid=new NetworkBuffer();invalid.values.addAll(java.util.List.of(1,999));
                  try { Envelope.SERIALIZER.read(invalid);throw new AssertionError(); } catch(IllegalArgumentException correct) { }
                } }
                """);
        verify();
        Files.writeString(source("Envelope.java"),once+"\n// user edit\n");
        assertTrue(assertThrows(IllegalStateException.class,()->RetainedPacketMigrator.apply(root,List.of(migration))).getMessage().contains("source edits"));
    }

    @Test void rejectsCatalogEntriesWithUnprovenPayloadAndDuplicateIds() {
        var classes=catalog();classes.get(SECOND).methods.removeIf(m->m.name.equals("<clinit>"));
        assertTrue(WireDispatch.scan(classes.get(UNION),classes::get).isEmpty());
        classes=catalog();
        var init=classes.get(TAGS).methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();
        for(var i:init.instructions)if(i instanceof IntInsnNode literal&&literal.operand==42)literal.operand=17;
        var sameIds=classes;assertTrue(WireDispatch.scan(sameIds.get(UNION),sameIds::get).isEmpty());
    }

    @Test void provesMappedStringAndWideScalarCarriers() {
        for (var definition : List.of("Ljava/lang/String;:STRING_UTF8", "J:LONG")) {
            var parts = definition.split(":");
            String descriptor = parts[0];
            var classes = catalog();
            var carrier = WireFixtures.node(FIRST, List.of("label:" + descriptor), List.of(parts[1]), List.of(0), true);
            carrier.methods.add(classes.get(FIRST).methods.stream().filter(m -> m.name.equals("kind")).findFirst().orElseThrow());
            carrier.methods.removeIf(m -> m.name.equals("<clinit>"));
            var getter = carrier.visitMethod(Opcodes.ACC_PUBLIC, "label", "()" + descriptor, null, null);
            getter.visitVarInsn(Opcodes.ALOAD, 0);
            getter.visitFieldInsn(Opcodes.GETFIELD, FIRST, "label", descriptor);
            getter.visitInsn(Type.getType(descriptor).getOpcode(Opcodes.IRETURN));
            getter.visitMaxs(2, 1); getter.visitEnd();
            var init = carrier.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitFieldInsn(Opcodes.GETSTATIC, WireSchema.BYTE_CODECS, parts[1], WireSchema.STREAM_CODEC);
            init.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", BOOTSTRAP,
                    new Handle(Opcodes.H_NEWINVOKESPECIAL, FIRST, "<init>", "(" + descriptor + ")V", false));
            init.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", BOOTSTRAP,
                    new Handle(Opcodes.H_INVOKEVIRTUAL, FIRST, "label", "()" + descriptor, false));
            init.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraft/network/codec/StreamCodec", "map",
                    "(Ljava/util/function/Function;Ljava/util/function/Function;)" + WireSchema.STREAM_CODEC, true);
            init.visitFieldInsn(Opcodes.PUTSTATIC, FIRST, "STREAM_CODEC", WireSchema.STREAM_CODEC);
            init.visitInsn(Opcodes.RETURN); init.visitMaxs(3, 0); init.visitEnd();
            classes.put(FIRST, carrier);
            var dispatch = WireDispatch.scan(classes.get(UNION), classes::get).orElseThrow(() -> new IllegalStateException(definition));
            assertEquals(descriptor, dispatch.variants().getFirst().payload().components().getFirst().descriptor());
        }
    }

    @Test void constantArrayLoopsAndFourFieldReordersUseTheSharedProjection() throws Exception {
        var before=fixedBefore();
        var after=WireFixtures.node(before.name,List.of("prefix:I","lines:Ljava/util/List;","choice:L"+TAGS+";","stamp:J"),
                List.of("VAR_INT","STRING_UTF8",TAGS+"#STREAM_CODEC","LONG"),List.of(0,1,2,3),true);
        after.recordComponents.get(1).signature="Ljava/util/List<Ljava/lang/String;>;";
        var init=after.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();
        for(var i:init.instructions.toArray())if(i instanceof FieldInsnNode field&&field.name.equals("STRING_UTF8")) {
            var code=new InsnList();code.add(new IntInsnNode(Opcodes.BIPUSH,17));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,WireSchema.BYTE_CODECS,"stringUtf8","(I)"+WireSchema.STREAM_CODEC,true));
            code.add(new InsnNode(Opcodes.ICONST_3));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,WireSchema.BYTE_CODECS,"fixedSizeList","(I)Lnet/minecraft/network/codec/StreamCodec$CodecOperation;",true));
            code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"net/minecraft/network/codec/StreamCodec","apply","(Lnet/minecraft/network/codec/StreamCodec$CodecOperation;)"+WireSchema.STREAM_CODEC,true));
            init.instructions.insertBefore(i,code);init.instructions.remove(i);
        }
        var tags=catalog();
        var plan=WireMigration.plan(before,after,name->null,tags::get,(oldField,newField)->new BooleanEnumProof.Ids(17,42)).orElseThrow();
        assertEquals(List.of(0,2,1,3),plan.bindings().stream().map(WireMigration.Binding::source).toList());
        write("Message.java","""
                package example;
                record Message(int prefix, boolean enabled, java.util.List<String> lines, long stamp) {
                  static final NetworkBuffer.Type<Message> SERIALIZER=new NetworkBuffer.Type<>() {
                    public void write(NetworkBuffer out,Message data) {
                      out.write(NetworkBuffer.VAR_INT,data.prefix());out.write(NetworkBuffer.BOOLEAN,data.enabled());
                      out.write(NetworkBuffer.STRING,data.lines().get(0));out.write(NetworkBuffer.STRING,data.lines().get(1));out.write(NetworkBuffer.STRING,data.lines().get(2));
                      out.write(NetworkBuffer.LONG,data.stamp());
                    }
                    public Message read(NetworkBuffer in) { return new Message(in.read(NetworkBuffer.VAR_INT),in.read(NetworkBuffer.BOOLEAN),java.util.List.of(in.read(NetworkBuffer.STRING),in.read(NetworkBuffer.STRING),in.read(NetworkBuffer.STRING)),in.read(NetworkBuffer.LONG)); }
                  };
                }
                """);
        var migration=PacketMigrationScanner.Migration.wire(plan).withPacket(packet("Message"));
        RetainedPacketMigrator.apply(root,List.of(migration));
        write("Check.java","""
                package example;
                public class Check { public static void verify() {
                  var value=new Message(77,true,java.util.List.of("alpha","beta","gamma"),99L);
                  var buffer=new NetworkBuffer();Message.SERIALIZER.write(buffer,value);
                  if(!buffer.values.equals(java.util.List.of(77,"alpha","beta","gamma",42,99L)))throw new AssertionError(buffer.values);
                  if(!Message.SERIALIZER.read(buffer).equals(value)||buffer.index!=6)throw new AssertionError();
                  try {Message.SERIALIZER.write(new NetworkBuffer(),new Message(1,false,java.util.List.of("one"),1));throw new AssertionError();}catch(IllegalArgumentException correct) { }
                  try {Message.SERIALIZER.write(new NetworkBuffer(),new Message(1,false,java.util.List.of("abcdefghijklmnopqr","b","c"),1));throw new AssertionError();}catch(IllegalArgumentException correct) { }
                } }
                """);
        verify();
        for(var method:before.methods)if(method.name.equals("write"))for(var i:method.instructions)if(i instanceof IincInsnNode step)step.incr=2;
        assertTrue(WireSchema.scan(before).isEmpty());
    }

    @Test void movesThreeDifferentScalarFieldsAndRetainsValuesWhenNestedObjectIsAbsent() throws Exception {
        String element="unrelated/Holder", leaf="unrelated/Bounds", wrapper="unrelated/Wrapper";
        var oldLeaf=new ClassNode();oldLeaf.name=leaf;oldLeaf.superName="java/lang/Object";
        for(var field:List.of("distance:J","fraction:D","seen:Z")){var pair=field.split(":");oldLeaf.visitField(Opcodes.ACC_PUBLIC,pair[0],pair[1],null,null).visitEnd();}
        var ctor=oldLeaf.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(1,1);ctor.visitEnd();
        var setter=oldLeaf.visitMethod(Opcodes.ACC_PUBLIC,"set","(JDZ)V",null,null);int slot=1;
        for(var field:List.of("distance:J","fraction:D","seen:Z")){var pair=field.split(":");setter.visitVarInsn(Opcodes.ALOAD,0);var type=Type.getType(pair[1]);setter.visitVarInsn(type.getOpcode(Opcodes.ILOAD),slot);setter.visitFieldInsn(Opcodes.PUTFIELD,leaf,pair[0],pair[1]);slot+=type.getSize();}setter.visitInsn(Opcodes.RETURN);setter.visitMaxs(3,6);setter.visitEnd();
        String buf="net/minecraft/network/FriendlyByteBuf";
        var writer=oldLeaf.visitMethod(Opcodes.ACC_PUBLIC,"write","(L"+buf+";)V",null,null);
        for(var field:List.of("distance:J:Long","fraction:D:Double","seen:Z:Boolean"))scalar(writer,leaf,buf,false,field);
        writer.visitInsn(Opcodes.RETURN);writer.visitMaxs(3,2);writer.visitEnd();
        var reader=oldLeaf.visitMethod(Opcodes.ACC_STATIC,"read","(L"+buf+";)L"+leaf+";",null,null);
        reader.visitTypeInsn(Opcodes.NEW,leaf);reader.visitInsn(Opcodes.DUP);reader.visitMethodInsn(Opcodes.INVOKESPECIAL,leaf,"<init>","()V",false);reader.visitVarInsn(Opcodes.ASTORE,1);reader.visitVarInsn(Opcodes.ALOAD,1);
        for(var field:List.of("Long:J","Double:D","Boolean:Z")){var pair=field.split(":");reader.visitVarInsn(Opcodes.ALOAD,0);reader.visitMethodInsn(Opcodes.INVOKEVIRTUAL,buf,"read"+pair[0],"()"+pair[1],false);}
        reader.visitMethodInsn(Opcodes.INVOKEVIRTUAL,leaf,"set","(JDZ)V",false);reader.visitVarInsn(Opcodes.ALOAD,1);reader.visitInsn(Opcodes.ARETURN);reader.visitMaxs(6,2);reader.visitEnd();
        var holder=WireFixtures.node(element,List.of("bounds:L"+leaf+";"),List.of(leaf+"#STREAM_CODEC"),List.of(0),true);
        var targetWrapper=WireFixtures.node(wrapper,List.of("data:L"+element+";","distance:J","fraction:D","seen:Z"),List.of(element+"#STREAM_CODEC","LONG","DOUBLE","BOOL"),List.of(0,1,2,3),true);
        var newLeaf=new ClassNode();newLeaf.name=leaf;
        var before=Map.of(element,holder,leaf,oldLeaf);var after=Map.of(element,holder,leaf,newLeaf,wrapper,targetWrapper);
        var move=WireFieldMove.scan(element,targetWrapper,before::get,after::get).orElseThrow();
        assertEquals(3,move.fields().size());
        write("Event.java","""
                package example;
                import java.util.*;
                import java.lang.annotation.*;
                @Target({ElementType.PARAMETER,ElementType.TYPE_USE}) @interface Nullable { }
                record Event(List<Mapping> entries) { static final NetworkBuffer.Type<Event> SERIALIZER=NetworkBufferTemplate.template(Mapping.SERIALIZER.list(),Event::entries,Event::new); }
                record Mapping(String key,int extra,Content body) {
                  static final NetworkBuffer.Type<Mapping> SERIALIZER=NetworkBufferTemplate.template(NetworkBuffer.STRING,Mapping::key,NetworkBuffer.VAR_INT,Mapping::extra,Content.SERIALIZER,Mapping::body,Mapping::new);
                  Mapping copy(){return new Mapping(key,extra+1,body);}
                }
                record Content(boolean active,@Nullable Leaf bounds,int rank) {
                  static final NetworkBuffer.Type<Content> SERIALIZER=NetworkBufferTemplate.template(NetworkBuffer.BOOLEAN,Content::active,Leaf.SERIALIZER.optional(),Content::bounds,NetworkBuffer.VAR_INT,Content::rank,Content::new);
                }
                record Leaf(long distance,String title,double fraction,boolean seen) {
                  static final NetworkBuffer.Type<Leaf> SERIALIZER=new NetworkBuffer.Type<>(){
                    public void write(NetworkBuffer out,Leaf data){out.write(NetworkBuffer.STRING,data.title());out.write(NetworkBuffer.LONG,data.distance());out.write(NetworkBuffer.DOUBLE,data.fraction());out.write(NetworkBuffer.BOOLEAN,data.seen());}
                    public Leaf read(NetworkBuffer in){var title=in.read(NetworkBuffer.STRING);var offset=in.read(NetworkBuffer.LONG);var ratio=in.read(NetworkBuffer.DOUBLE);var visibility=in.read(NetworkBuffer.BOOLEAN);return new Leaf(offset,title,ratio,visibility);}
                  };
                }
                """);
        var sourceUnit=new com.github.javaparser.JavaParser().parse(Files.readString(source("Event.java"))).getResult().orElseThrow();
        for(var declaration:List.copyOf(sourceUnit.getTypes()))if(declaration.isRecordDeclaration()&&!declaration.getNameAsString().equals("Event")){
            write(declaration.getNameAsString()+".java","package example; import java.util.*;\n"+declaration);declaration.remove();
        }
        Files.writeString(source("Event.java"),sourceUnit.toString());
        var migration=PacketMigrationScanner.Migration.move(List.of(0),move).withPacket(packet("Event"));
        RetainedPacketMigrator.apply(root,List.of(migration));String once=Files.readString(source("Event.java"));
        RetainedPacketMigrator.apply(root,List.of(migration));assertEquals(once,Files.readString(source("Event.java")));
        write("Check.java","""
                package example;
                public class Check {public static void verify(){
                  var absent=new Mapping("a",3,new Content(true,null,5),123L,0.75d,true);
                  var present=new Mapping("b",4,new Content(false,new Leaf(456L,"title",0.25d,false),6));
                  for(var value:java.util.List.of(absent,present)){
                    var buffer=new NetworkBuffer();Mapping.SERIALIZER.write(buffer,value);
                    var decoded=Mapping.SERIALIZER.read(buffer);
                    if(!decoded.equals(value)||buffer.index!=buffer.values.size())throw new AssertionError(decoded);
                  }
                  if(absent.copy().distance()!=123L||absent.copy().fraction()!=0.75d||!absent.copy().seen())throw new AssertionError();
                }}
                """);verify();
        ((MethodNode)setter).instructions.insertBefore(((MethodNode)setter).instructions.getLast(),new InsnNode(Opcodes.IADD));
        assertTrue(WireFieldMove.scan(element,targetWrapper,before::get,after::get).isEmpty());
    }

    private static Map<String,ClassNode> catalog() {
        Map<String,ClassNode> nodes=new LinkedHashMap<>();
        var first=WireFixtures.node(FIRST,List.of("label:Ljava/lang/String;"),List.of("STRING_UTF8"),List.of(0),true);
        var second=WireFixtures.node(SECOND,List.of("text:Ljava/lang/String;","quantity:I","serial:J"),List.of("STRING_UTF8","VAR_INT","LONG"),List.of(0,1,2),true);
        for(var variant:List.of(first,second)) {
            var type=variant.visitMethod(Opcodes.ACC_PUBLIC,"kind","()L"+TAGS+";",null,null);
            type.visitFieldInsn(Opcodes.GETSTATIC,TAGS,variant==first?"ALPHA":"BETA","L"+TAGS+";");type.visitInsn(Opcodes.ARETURN);type.visitMaxs(1,1);type.visitEnd();
            nodes.put(variant.name,variant);
        }
        var tags=new ClassNode();new ClassReader(PacketMigrationScannerTest.binaryEnumClass(TAGS,"ALPHA",17,"BETA",42)).accept(tags,0);
        tags.visitField(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,"codec",WireSchema.STREAM_CODEC,null,null).visitEnd();
        var getter=tags.visitMethod(Opcodes.ACC_PUBLIC,"codec","()"+WireSchema.STREAM_CODEC,null,null);getter.visitVarInsn(Opcodes.ALOAD,0);getter.visitFieldInsn(Opcodes.GETFIELD,TAGS,"codec",WireSchema.STREAM_CODEC);getter.visitInsn(Opcodes.ARETURN);getter.visitMaxs(1,1);getter.visitEnd();
        var ctor=tags.methods.stream().filter(m->m.name.equals("<init>")).findFirst().orElseThrow();ctor.desc="(Ljava/lang/String;II"+WireSchema.STREAM_CODEC+")V";
        var stores=new InsnList();stores.add(new VarInsnNode(Opcodes.ALOAD,0));stores.add(new VarInsnNode(Opcodes.ALOAD,4));stores.add(new FieldInsnNode(Opcodes.PUTFIELD,TAGS,"codec",WireSchema.STREAM_CODEC));ctor.instructions.insertBefore(ctor.instructions.getLast(),stores);ctor.maxLocals=5;
        var init=tags.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();int which=0;
        for(var i:init.instructions.toArray())if(i instanceof MethodInsnNode call&&call.name.equals("<init>")&&call.owner.equals(TAGS)) {
            call.desc=ctor.desc;init.instructions.insertBefore(call,new FieldInsnNode(Opcodes.GETSTATIC,which++==0?FIRST:SECOND,"STREAM_CODEC",WireSchema.STREAM_CODEC));
        }
        nodes.put(TAGS,tags);
        var union=new ClassNode();union.name=UNION;union.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE;union.superName="java/lang/Object";
        var clinit=union.visitMethod(Opcodes.ACC_STATIC,"<clinit>","()V",null,null);
        clinit.visitFieldInsn(Opcodes.GETSTATIC,TAGS,"STREAM_CODEC",WireSchema.STREAM_CODEC);
        clinit.visitInvokeDynamicInsn("apply","()Ljava/util/function/Function;",BOOTSTRAP,new Handle(Opcodes.H_INVOKEINTERFACE,UNION,"kind","()L"+TAGS+";",true));
        clinit.visitInvokeDynamicInsn("apply","()Ljava/util/function/Function;",BOOTSTRAP,new Handle(Opcodes.H_INVOKEVIRTUAL,TAGS,"codec","()"+WireSchema.STREAM_CODEC,false));
        clinit.visitMethodInsn(Opcodes.INVOKEINTERFACE,"net/minecraft/network/codec/StreamCodec","dispatch","()"+WireSchema.STREAM_CODEC,true);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC,UNION,"STREAM_CODEC",WireSchema.STREAM_CODEC);clinit.visitInsn(Opcodes.RETURN);clinit.visitMaxs(3,0);clinit.visitEnd();nodes.put(UNION,union);
        return nodes;
    }

    private static ClassNode fixedBefore() {
        var node=new ClassNode();node.name="unrelated/Message";node.superName="java/lang/Object";
        for(var field:List.of("prefix:I","enabled:Z","lines:[Ljava/lang/String;","stamp:J")) {var p=field.split(":",2);node.visitField(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,p[0],p[1],null,null).visitEnd();}
        String buffer="net/minecraft/network/FriendlyByteBuf";
        for(boolean read:List.of(true,false)) {
            var method=node.visitMethod(Opcodes.ACC_PRIVATE,read?"<init>":"write","(L"+buffer+";)V",null,null);
            if(read){method.visitVarInsn(Opcodes.ALOAD,0);method.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);}
            for(var field:List.of("prefix:I:VarInt","enabled:Z:Boolean"))scalar(method,node.name,buffer,read,field);
            if(read){method.visitVarInsn(Opcodes.ALOAD,0);method.visitInsn(Opcodes.ICONST_3);method.visitTypeInsn(Opcodes.ANEWARRAY,"java/lang/String");method.visitFieldInsn(Opcodes.PUTFIELD,node.name,"lines","[Ljava/lang/String;");}
            method.visitInsn(Opcodes.ICONST_0);method.visitVarInsn(Opcodes.ISTORE,2);Label loop=new Label(),end=new Label();method.visitLabel(loop);method.visitVarInsn(Opcodes.ILOAD,2);method.visitInsn(Opcodes.ICONST_3);method.visitJumpInsn(Opcodes.IF_ICMPGE,end);
            if(!read)method.visitVarInsn(Opcodes.ALOAD,1);
            method.visitVarInsn(Opcodes.ALOAD,0);method.visitFieldInsn(Opcodes.GETFIELD,node.name,"lines","[Ljava/lang/String;");method.visitVarInsn(Opcodes.ILOAD,2);
            if(read){method.visitVarInsn(Opcodes.ALOAD,1);method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,buffer,"readUtf","()Ljava/lang/String;",false);method.visitInsn(Opcodes.AASTORE);}
            else {method.visitInsn(Opcodes.AALOAD);method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,buffer,"writeUtf","(Ljava/lang/String;)V",false);}
            method.visitIincInsn(2,1);method.visitJumpInsn(Opcodes.GOTO,loop);method.visitLabel(end);scalar(method,node.name,buffer,read,"stamp:J:Long");method.visitInsn(Opcodes.RETURN);method.visitMaxs(5,3);method.visitEnd();
        }
        return node;
    }
    private static void scalar(MethodVisitor method,String owner,String buffer,boolean read,String field) {
        var p=field.split(":");method.visitVarInsn(Opcodes.ALOAD,read?0:1);
        method.visitVarInsn(Opcodes.ALOAD,read?1:0);
        if(!read)method.visitFieldInsn(Opcodes.GETFIELD,owner,p[0],p[1]);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,buffer,(read?"read":"write")+p[2],read?"()"+p[1]:"("+p[1]+")V",false);
        if(read)method.visitFieldInsn(Opcodes.PUTFIELD,owner,p[0],p[1]);
    }
    private PacketUpdater.RetainedPacket packet(String name){return new PacketUpdater.RetainedPacket(name,name+".SERIALIZER","before","after");}
    private Path source(String name){return root.resolve("src/main/java/example/"+name);}
    private void write(String name,String text)throws Exception{Files.createDirectories(source(name).getParent());Files.writeString(source(name),text);}
    private void verify() throws Exception {
        write("NetworkBuffer.java",BUFFER);
        var args=new ArrayList<String>(List.of("-d",root.resolve("classes").toString()));
        try(var files=Files.list(source("Check.java").getParent())){files.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(args::add);}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        try(var loader=new URLClassLoader(new java.net.URL[]{root.resolve("classes").toUri().toURL()})){loader.loadClass("example.Check").getMethod("verify").invoke(null);}
    }
    private static final String BUFFER="""
        package example;
        import java.util.*;import java.util.function.*;
        class NetworkBuffer {
          interface Type<T>{void write(NetworkBuffer b,T v);T read(NetworkBuffer b);
            default Type<T> optional(){var self=this;return new Type<>(){public void write(NetworkBuffer b,T v){b.write(BOOLEAN,v!=null);if(v!=null)self.write(b,v);}public T read(NetworkBuffer b){return b.read(BOOLEAN)?self.read(b):null;}};}
            default Type<List<T>> list(){var self=this;return new Type<>(){public void write(NetworkBuffer b,List<T> v){b.write(VAR_INT,v.size());for(var x:v)self.write(b,x);}public List<T> read(NetworkBuffer b){int n=b.read(VAR_INT);var v=new ArrayList<T>();for(int i=0;i<n;i++)v.add(self.read(b));return v;}};}
            default <R> Type<R> transform(Function<T,R> decode,Function<R,T> encode){var self=this;return new Type<>(){public void write(NetworkBuffer b,R v){self.write(b,encode.apply(v));}public R read(NetworkBuffer b){return decode.apply(self.read(b));}};}
          }
          static class Scalar<T> implements Type<T>{public void write(NetworkBuffer b,T v){b.values.add(v);}public T read(NetworkBuffer b){return (T)b.values.get(b.index++);}}
          static final Type<String> STRING=new Scalar<>();static final Type<Integer> VAR_INT=new Scalar<>();static final Type<Long> LONG=new Scalar<>();static final Type<Double> DOUBLE=new Scalar<>();static final Type<Boolean> BOOLEAN=new Scalar<>();
          final List<Object> values=new ArrayList<>();int index;
          <T> void write(Type<T> c,T v){c.write(this,v);}<T>T read(Type<T> c){return c.read(this);}
        }
        class NetworkBufferTemplate{
          static <T,A> NetworkBuffer.Type<T> template(NetworkBuffer.Type<A>a,Function<T,A>ga,Function<A,T>create){return new NetworkBuffer.Type<>(){public void write(NetworkBuffer n,T v){n.write(a,ga.apply(v));}public T read(NetworkBuffer n){return create.apply(n.read(a));}};}
          static <T,A,B,C> NetworkBuffer.Type<T> template(NetworkBuffer.Type<A>a,Function<T,A>ga,NetworkBuffer.Type<B>b,Function<T,B>gb,NetworkBuffer.Type<C>c,Function<T,C>gc,Create3<T,A,B,C>create){return new NetworkBuffer.Type<>(){public void write(NetworkBuffer n,T v){n.write(a,ga.apply(v));n.write(b,gb.apply(v));n.write(c,gc.apply(v));}public T read(NetworkBuffer n){return create.apply(n.read(a),n.read(b),n.read(c));}};}
          interface Create3<T,A,B,C>{T apply(A a,B b,C c);}
          static <T,A,B,C,D,E,F> NetworkBuffer.Type<T> template(NetworkBuffer.Type<A>a,Function<T,A>ga,NetworkBuffer.Type<B>b,Function<T,B>gb,NetworkBuffer.Type<C>c,Function<T,C>gc,NetworkBuffer.Type<D>d,Function<T,D>gd,NetworkBuffer.Type<E>e,Function<T,E>ge,NetworkBuffer.Type<F>f,Function<T,F>gf,Create<T,A,B,C,D,E,F>create){return new NetworkBuffer.Type<>(){public void write(NetworkBuffer n,T v){n.write(a,ga.apply(v));n.write(b,gb.apply(v));n.write(c,gc.apply(v));n.write(d,gd.apply(v));n.write(e,ge.apply(v));n.write(f,gf.apply(v));}public T read(NetworkBuffer n){return create.apply(n.read(a),n.read(b),n.read(c),n.read(d),n.read(e),n.read(f));}};}
          interface Create<T,A,B,C,D,E,F>{T apply(A a,B b,C c,D d,E e,F f);}
        }
        """;
}
