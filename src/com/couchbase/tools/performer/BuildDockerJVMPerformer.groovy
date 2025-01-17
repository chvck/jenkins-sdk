package com.couchbase.tools.performer

import com.couchbase.context.environments.Environment
import com.couchbase.tools.tags.TagProcessor
import com.couchbase.versions.ImplementationVersion
import groovy.transform.CompileStatic

import java.util.stream.Collectors

/**
 * Building the JVM performers requires some pom.xml manipulation to pull in the correct transitive core-io version.
 *
 * There are three main modes, resulting in different manipulations, and selected by passing in an Optional sdkVersion:
 * 1. Compiling master.
 * 2. Compiling a specific sdkVersion.
 * 3. Compiling a specific Gerrit changeset.
 */
@CompileStatic
class BuildDockerJVMPerformer {
    /**
     * @param path absolute path to above 'couchbase-jvm-clients'
     * @param client 'java', 'kotlin'
     * @param sdkVersion '3.2.3' or 'refs/changes/94/184294/1'.  If not present, it indicates to just build master.
     */
    static void build(Environment imp, String path, String client, Optional<String> sdkVersion, String imageName, boolean onlySource = false) {
        var isGerrit = sdkVersion.isPresent() && sdkVersion.get().startsWith("refs/")
        if (isGerrit) {
            imp.tempDir {
                // We need:
                // 1. transactions-fit-performer and couchbase-jvm-clients to exist at the same level, since the Dockerfile from
                //    the latter needs to copy files from the former.
                // 2. The performer Dockerfile explicitly mentions those two directories.
                // 3. Don't want to trash any existing couchbase-jvm-clients folder.
                //
                // So we do this - checkout the source to a temp dir, move it over to next to transactions-fit-performer
                // with a different partially randomised name ("couchbase-jvm-clients-34cad"), and modify the Dockerfile
                // to point there.
                //
                // We leave those temporary directories afterwards for now as a) this is probably running on CI and b)
                // deleting files is always a little risky.
                imp.execute("pwd", false, true, true)
                imp.execute("git clone https://github.com/couchbase/couchbase-jvm-clients", false, true, true)
                var temp = "temp-${UUID.randomUUID().toString().substring(0, 4)}"
                imp.dir("couchbase-jvm-clients") {
                    imp.execute("git fetch https://review.couchbase.org/couchbase-jvm-clients ${sdkVersion.get()}", false, true, true)
                    imp.execute("git checkout FETCH_HEAD", false, true, true)
                    imp.execute("git log -n 1", false, true, true)
                    def file = new File("${imp.currentDir()}/java-fit-performer/Dockerfile")
                    def modified = file.readLines().stream()
                            .map(line -> {
                                if (line == "COPY couchbase-jvm-clients couchbase-jvm-clients/") {
                                    return "COPY couchbase-jvm-clients-${temp} couchbase-jvm-clients/"
                                } else {
                                    return line
                                }
                            })
                            .collect(Collectors.toList())
                    file.write(modified.join("\n"))
                }
                imp.execute("mv couchbase-jvm-clients ${path}/couchbase-jvm-clients-${temp}")
                imp.dirAbsolute("${path}/couchbase-jvm-clients-${temp}") {
                    writeParentPomFile(imp, false)
                }
                imp.dirAbsolute(path) {
                    imp.execute("docker build -f couchbase-jvm-clients-${temp}/${client}-fit-performer/Dockerfile -t ${imageName} .", false, true, true)
                }
            }
        }
        else {
            imp.dirAbsolute(path) {
                imp.dir("couchbase-jvm-clients") {
                    writeParentPomFile(imp, sdkVersion.isPresent())
                    imp.dir("${client}-fit-performer") {
                        sdkVersion.ifPresent(ver -> {
                            writePerformerPomFile(imp, client + "-client" + (client == "scala" ? "_2.12" : ""), ver)
                            TagProcessor.processTags(new File(imp.currentDir() + "/src"), ImplementationVersion.from(ver), false)
                        })
                    }
                }
                if (!onlySource) {
                    imp.execute("docker build -f couchbase-jvm-clients/${client}-fit-performer/Dockerfile -t ${imageName} .", false, true, true)
                }
            }
        }
    }

    /**
     * Updates couchbase-jvm-client/pom.xml.
     */
    private static List writeParentPomFile(Environment imp, boolean specificVersion) {
        def pom = new File("${imp.currentDir()}/pom.xml")
        imp.log("Updating ${pom.getAbsolutePath()}")
        def lines = pom.readLines()
        def out = new ArrayList<String>()
        def commentFromLine = -1
        def commentUntilLine = -1

        for (int i = 0; i < lines.size(); i++) {
            def line = lines[i]
            boolean alreadyCommented = line.size() > 0 && line.startsWith("<!--")
            boolean commentLine = false

            if (specificVersion) {
                // Have to comment out the core-io dependency as it overrides the java-client's transitive dependency.
                // E.g. we end up with java-client 3.0.0 and core-io 2.3.3-SNAPSHOT.  And it's challenging to programmatically
                // select the correct core-io build for a java-client build, when working with SNAPSHOTs.
                // This assumes the core-io dependency is the first listed.
                if (line.contains("dependencies") && lines.get(i + 3).contains("core-io")) {
                    commentFromLine = i + 1
                    commentUntilLine = i + 5
                }

                // Have to comment the other modules out as they don't compile when the core-io dependency is removed
                commentLine = (line.contains("<module>") && !line.contains("fit-performer"))
                        || (commentFromLine != -1 && (i >= commentFromLine && i <= commentUntilLine))
            }

            // Have to uncomment the performers
            def uncommentLine = line.contains("<module>") && line.contains("fit-performer")

            if (uncommentLine) {
                line = line.replace("<!--", "").replace("!-->", "").replace("-->", "")
            }

            if (commentLine && !alreadyCommented) {
                out.add("<!-- " + line + " !-->")
            }
            else {
                out.add(line)
            }
        }

        def w = pom.newWriter()
        w << out.join(System.lineSeparator()) + System.getProperty("line.separator")
        w.close()
    }

    /**
     * Updates the performer's pom.xml to build with the SDK under test.
     */
    private static List writePerformerPomFile(Environment imp, String lookingFor, String versionToInsert) {
        def pom = new File("${imp.currentDir()}/pom.xml")
        imp.log("Updating ${pom.getAbsolutePath()}")
        def lines = pom.readLines()
        def out = new ArrayList<String>()

        for (int i = 0; i < lines.size(); i++) {
            def line = lines[i]

            if (line.contains("<artifactId>${lookingFor}</artifactId>")) {
                // Skip over any existing version
                if (lines[i + 1].contains("<version>")) {
                    i += 1
                }
                out.add(line)
                out.add("            <version>" + versionToInsert + "</version>")
            }
            else {
                out.add(line)
            }
        }

        def w = pom.newWriter()
        w << out.join(System.lineSeparator()) + System.getProperty("line.separator")
        w.close()
    }
}