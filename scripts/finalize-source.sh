#!/usr/bin/env bash
set -euo pipefail

plan_file=${1:?usage: finalize-source.sh <plan.json> <target-directory> <nightstorm-bin>}
target_directory=${2:?usage: finalize-source.sh <plan.json> <target-directory> <nightstorm-bin>}
nightstorm_bin=${3:?usage: finalize-source.sh <plan.json> <target-directory> <nightstorm-bin>}

read_plan() {
  jq -r "$1" "$plan_file"
}

minecraft_version=$(read_plan '.minecraftVersion')
minestom_repository=$(read_plan '.minestomRepository')
minestom_tag=$(read_plan '.minestomTag')
minestom_release_date=$(read_plan '.minestomReleaseDate')
data_generator_repository=$(read_plan '.minestomDataGeneratorRepository')
data_generator_commit=$(read_plan '.minestomDataGeneratorCommit')
branch=$(read_plan '.branch')
base_ref=$(read_plan '.baseRef')
reason=$(read_plan '.reason')
artifact_version=$(read_plan '.artifactVersion')

server_jar="$target_directory/.nightstorm/minecraft-server.jar"
baseline_server_jar="$target_directory/.nightstorm/baseline-minecraft-server.jar"
loom_server_jar="$HOME/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-server-deobf/$minecraft_version/minecraft-server-deobf-$minecraft_version.jar"
test -f "$loom_server_jar"
cp "$loom_server_jar" "$server_jar"

test -f "$baseline_server_jar"

"$nightstorm_bin" update-packets \
  --baseline-jar "$baseline_server_jar" \
  --jar "$server_jar" \
  --source "$target_directory" \
  --output "$target_directory/.nightstorm/packet-schema.json"
rm "$baseline_server_jar"

release_notes="$target_directory/.nightstorm/release-notes.md"
printf '%s\n' 'Generated from Minestom and Minecraft metadata. See `.nightstorm/manifest.json` on the release branch.' > "$release_notes"
if [[ -f "$target_directory/.nightstorm/packet-warnings.md" ]]; then
  printf '\n' >> "$release_notes"
  cat "$target_directory/.nightstorm/packet-warnings.md" >> "$release_notes"
fi

pushd "$target_directory" >/dev/null
./gradlew :code-generators:run --no-daemon
./gradlew assemble --no-daemon
popd >/dev/null

jq -n \
  --arg minecraftVersion "$minecraft_version" \
  --arg minestomRepository "$minestom_repository" \
  --arg minestomTag "$minestom_tag" \
  --arg minestomReleaseDate "$minestom_release_date" \
  --arg dataGeneratorRepository "$data_generator_repository" \
  --arg dataGeneratorCommit "$data_generator_commit" \
  --arg branch "$branch" \
  --arg baseRef "$base_ref" \
  --arg reason "$reason" \
  --arg artifactVersion "$artifact_version" \
  '{minecraftVersion: $minecraftVersion, minestomRepository: $minestomRepository, minestomTag: $minestomTag, minestomReleaseDate: $minestomReleaseDate, minestomDataGeneratorRepository: $dataGeneratorRepository, minestomDataGeneratorCommit: $dataGeneratorCommit, branch: $branch, baseRef: $baseRef, reason: $reason, artifactVersion: $artifactVersion}' \
  > "$target_directory/.nightstorm/manifest.json"

cat > "$target_directory/.nightstorm/publish.gradle" <<'EOF'
allprojects { project ->
    project.plugins.withId("maven-publish") {
        project.publishing {
            repositories {
                maven {
                    name = "NightstormPages"
                    url = uri(System.getenv("NIGHTSTORM_MAVEN_DIRECTORY"))
                }
            }
        }
        if (project == rootProject) {
            def nightstormVersion = System.getenv("NIGHTSTORM_VERSION")
            project.publishing.publications.create("nightstormData", org.gradle.api.publish.maven.MavenPublication) {
                groupId = "net.flowstom"
                artifactId = "nightstorm-data"
                version = nightstormVersion
                artifact(project.file(".nightstorm/data.jar"))
            }
            // File dependencies are not published. Add the separately published data jar to both
            // Gradle module metadata and the Maven POM without resolving it during this build.
            def publishedData = project.configurations.dependencyScope("nightstormPublishedData").get()
            project.dependencies.add(publishedData.name, "net.flowstom:nightstorm-data:${nightstormVersion}")
            project.configurations.named("runtimeElements") {
                extendsFrom(publishedData)
            }
        }
    }
    project.tasks.withType(Sign).configureEach {
        enabled = false
    }
}

gradle.projectsEvaluated {
    if (rootProject.plugins.hasPlugin("maven-publish")) {
        def nightstormVersion = System.getenv("NIGHTSTORM_VERSION")
        rootProject.publishing.publications.named("maven", org.gradle.api.publish.maven.MavenPublication) {
            groupId = "net.flowstom"
            artifactId = "nightstorm"
            version = nightstormVersion
        }
    }
}
EOF
