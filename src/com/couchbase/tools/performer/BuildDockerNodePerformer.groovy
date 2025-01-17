package com.couchbase.tools.performer

import com.couchbase.context.environments.Environment
import com.couchbase.tools.tags.TagProcessor
import com.couchbase.versions.ImplementationVersion

class BuildDockerNodePerformer {
    private static Boolean write_couchbase = false

    static void build(Environment imp, String path, Optional<String> sdkVersion, String imageName, Optional<String> sha, boolean onlySource = false) {
        imp.dirAbsolute(path) {
            imp.dir('transactions-fit-performer') {
                imp.dir("performers/node") {
                    writePackageFile(imp, sdkVersion, sha)
                    sdkVersion.ifPresent(v -> {
                        TagProcessor.processTags(new File(imp.currentDir()), ImplementationVersion.from(v), false)
                    })
                }
                if (!onlySource) {
                    imp.log("building docker container")
                    imp.execute("docker build -f performers/node/Dockerfile -t $imageName .", false, true, true)
                }
            }
        }
    }

    private static void writePackageFile(Environment imp, Optional<String> sdkVersion, Optional<String> sha) {
        def packageFile = new File("${imp.currentDir()}/package.json")
        def shaFile = new File("${imp.currentDir()}/sha.txt")
        def lines = packageFile.readLines()
        packageFile.write("")
        shaFile.write("")

        if (couchbaseInPackageFile(lines)) {
            for (int i = 0; i < lines.size(); i++) {
                def line = lines[i]

                if (line.contains("couchbase") && sdkVersion.isPresent()) {
                    if (sha.isPresent()) { //True if using snapshot version
                        shaFile.append(sha.get())
                    } else {
                        packageFile.append("\t\"couchbase\": \"^${sdkVersion.get()}\",\n")
                    }
                } else {
                    packageFile.append(line + "\n")
                }
            }
        } else {
            for (int i = 0; i < lines.size(); i++) {
                def line = lines[i]

                if (write_couchbase) {
                    packageFile.append("\t\"couchbase\": \"^${sdkVersion.get()}\",\n")
                    write_couchbase = false
                }
                if (line.contains("dependencies") && sdkVersion.isPresent()) {
                    if (sha.isPresent()) {
                        shaFile.append(sha.get())
                    } else {
                        write_couchbase = true
                    }
                    packageFile.append(line + "\n")
                } else {
                    packageFile.append(line + "\n")
                }
            }
        }
    }

    private static boolean couchbaseInPackageFile(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            def line = lines[i]
            if(line.contains("couchbase")) {
                return true
            }
        }
        return false
    }
}
