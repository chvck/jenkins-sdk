package com.couchbase.perf.sdk.stages


import com.couchbase.stages.servers.InitialiseCluster
import com.couchbase.stages.Stage
import groovy.json.JsonBuilder
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import com.couchbase.context.StageContext

/**
 * Outputs the runner config
 */
@CompileStatic
class RunRunner extends Stage {
    private final InitialiseCluster stageCluster
    private final InitialiseSDKPerformer stagePerf
    private final OutputPerformerConfig stageOutput

    RunRunner(InitialiseCluster cluster, InitialiseSDKPerformer stagePerf, OutputPerformerConfig stageOutput) {
        this.stageCluster = cluster
        this.stagePerf = stagePerf
        this.stageOutput = stageOutput
    }

    @Override
    String name() {
        return "Run for ${stageOutput.absoluteConfigFilename()}"
    }

    @CompileDynamic
    @Override
    void executeImpl(StageContext ctx) {
        def json = new JsonBuilder()

        //def hostname = stageCluster.hostname()
        // if (hostname.equals("localhost") && stagePerf.isDocker()) {
        //     // Note Docker requirement of host.docker.internal - https://stackoverflow.com/questions/24319662/from-inside-of-a-docker-container-how-do-i-connect-to-the-localhost-of-the-mach
        //     hostname = "host.docker.internal"
        // }
        ctx.inSourceDir {
            ctx.env.executeSimple("docker build -f sdk-driver/Dockerfile -t driver .")
            ctx.env.log(ctx.env.executeSimple("docker run --rm --network perf driver /app/" + stageOutput.outputFilename()))
        }
    }
}