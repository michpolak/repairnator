package fr.inria.spirals.repairnator.dockerpool;

import com.martiansoftware.jsap.FlaggedOption;
import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPException;
import com.martiansoftware.jsap.JSAPResult;
import fr.inria.spirals.repairnator.TravisInputBuild;
import fr.inria.spirals.repairnator.LauncherType;
import fr.inria.spirals.repairnator.LauncherUtils;
import fr.inria.spirals.repairnator.utils.Utils;
import fr.inria.spirals.repairnator.docker.DockerHelper;
import fr.inria.spirals.repairnator.notifier.EndProcessNotifier;
import fr.inria.spirals.repairnator.notifier.engines.NotifierEngine;
import fr.inria.spirals.repairnator.config.RepairnatorConfig;
import fr.inria.spirals.repairnator.dockerpool.serializer.EndProcessSerializer;
import fr.inria.spirals.repairnator.serializer.HardwareInfoSerializer;
import fr.inria.spirals.repairnator.serializer.engines.SerializerEngine;

import fr.inria.spirals.repairnator.states.LauncherMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Run one docker container with the pipeline per line of the input file builds.txt
 */
public class BuildAnalyzerLauncher {
    private static Logger LOGGER = LoggerFactory.getLogger(BuildAnalyzerLauncher.class);
    private List<SerializerEngine> engines;
    private RepairnatorConfig config;
    private EndProcessNotifier endProcessNotifier;
    private DockerPoolManager dockerPool = new DockerPoolManager();

    private BuildAnalyzerLauncher(String[] args) throws JSAPException {
        JSAP jsap = this.defineArgs();
        JSAPResult arguments = jsap.parse(args);
        LauncherUtils.checkArguments(jsap, arguments, LauncherType.DOCKERPOOL);

        this.initConfig(arguments);
        this.initSerializerEngines();
        this.initNotifiers();
    }

    private JSAP defineArgs() throws JSAPException {
        // Verbose output
        JSAP jsap = new JSAP();

        // -h or --help
        jsap.registerParameter(LauncherUtils.defineArgHelp());
        // -d or --debug
        jsap.registerParameter(LauncherUtils.defineArgDebug());
        // --runId
        jsap.registerParameter(LauncherUtils.defineArgRunId());
        // --bears
        jsap.registerParameter(LauncherUtils.defineArgBearsMode());
        // --checkstyle
        jsap.registerParameter(LauncherUtils.defineArgCheckstyleMode());
        // -i or --input
        jsap.registerParameter(LauncherUtils.defineArgInput());
        // -o or --output
        jsap.registerParameter(LauncherUtils.defineArgOutput(LauncherType.DOCKERPOOL,"Specify where to put serialized files from dockerpool"));
        // --dbhost
        jsap.registerParameter(LauncherUtils.defineArgMongoDBHost());
        // --dbname
        jsap.registerParameter(LauncherUtils.defineArgMongoDBName());
        // --notifyEndProcess
        jsap.registerParameter(LauncherUtils.defineArgNotifyEndProcess());
        // --smtpServer
        jsap.registerParameter(LauncherUtils.defineArgSmtpServer());
        // --smtpPort 
        jsap.registerParameter(LauncherUtils.defineArgSmtpPort());
        // --smtpTLS
        jsap.registerParameter(LauncherUtils.defineArgSmtpTLS());
        // --smtpUsername
        jsap.registerParameter(LauncherUtils.defineArgSmtpUsername());
        // --smtpPassword
        jsap.registerParameter(LauncherUtils.defineArgSmtpPassword());
        // --notifyto
        jsap.registerParameter(LauncherUtils.defineArgNotifyto());
        // -n or --name
        jsap.registerParameter(LauncherUtils.defineArgDockerImageName());
        // --skipDelete
        jsap.registerParameter(LauncherUtils.defineArgSkipDelete());
        // --createOutputDir
        jsap.registerParameter(LauncherUtils.defineArgCreateOutputDir());
        // -t or --threads
        jsap.registerParameter(LauncherUtils.defineArgNbThreads());
        // -g or --globalTimeout
        jsap.registerParameter(LauncherUtils.defineArgGlobalTimeout());
        // --pushurl
        jsap.registerParameter(LauncherUtils.defineArgPushUrl());
        // --ghOauth
        jsap.registerParameter(LauncherUtils.defineArgGithubOAuth());
        // --githubUserName
        jsap.registerParameter(LauncherUtils.defineArgGithubUserName());
        // --githubUserEmail
        jsap.registerParameter(LauncherUtils.defineArgGithubUserEmail());
        // --createPR
        jsap.registerParameter(LauncherUtils.defineArgCreatePR());

        FlaggedOption opt2 = new FlaggedOption("repairTools");
        opt2.setLongFlag("repairTools");
        opt2.setList(true);
        opt2.setListSeparator(',');
        opt2.setHelp("Specify one or several repair tools to use separated by commas (available tools might depend of your docker image)");
        opt2.setDefault("NopolAllTests");
        jsap.registerParameter(opt2);

        return jsap;
    }

    private void initConfig(JSAPResult arguments) {
        this.config = RepairnatorConfig.getInstance();

        if (LauncherUtils.getArgDebug(arguments)) {
            this.config.setDebug(true);
        }
        this.config.setRunId(LauncherUtils.getArgRunId(arguments));
        this.config.setGithubToken(LauncherUtils.getArgGithubOAuth(arguments));
        if (LauncherUtils.getArgBearsMode(arguments)) {
            this.config.setLauncherMode(LauncherMode.BEARS);
        } else if (LauncherUtils.getArgCheckstyleMode(arguments)) {
            this.config.setLauncherMode(LauncherMode.CHECKSTYLE);
        } else {
            this.config.setLauncherMode(LauncherMode.REPAIR);
        }
        this.config.setInputPath(LauncherUtils.getArgInput(arguments).getPath());
        this.config.setOutputPath(LauncherUtils.getArgOutput(arguments).getPath());
        this.config.setMongodbHost(LauncherUtils.getArgMongoDBHost(arguments));
        this.config.setMongodbName(LauncherUtils.getArgMongoDBName(arguments));
        this.config.setNotifyEndProcess(LauncherUtils.getArgNotifyEndProcess(arguments));
        this.config.setSmtpServer(LauncherUtils.getArgSmtpServer(arguments));
        this.config.setSmtpPort(LauncherUtils.getArgSmtpPort(arguments));
        this.config.setSmtpTLS(LauncherUtils.getArgSmtpTLS(arguments));
        this.config.setSmtpUsername(LauncherUtils.getArgSmtpUsername(arguments));
        this.config.setSmtpPassword(LauncherUtils.getArgSmtpPassword(arguments));
        this.config.setNotifyTo(LauncherUtils.getArgNotifyto(arguments));
        this.config.setDockerImageName(LauncherUtils.getArgDockerImageName(arguments));
        this.config.setSkipDelete(LauncherUtils.getArgSkipDelete(arguments));
        this.config.setCreateOutputDir(LauncherUtils.getArgCreateOutputDir(arguments));
        this.config.setLogDirectory(LauncherUtils.getArgLogDirectory(arguments));
        this.config.setNbThreads(LauncherUtils.getArgNbThreads(arguments));
        this.config.setGlobalTimeout(LauncherUtils.getArgGlobalTimeout(arguments));
        this.config.setGithubUserEmail(LauncherUtils.getArgGithubUserEmail(arguments));
        this.config.setGithubUserName(LauncherUtils.getArgGithubUserName(arguments));
        if (LauncherUtils.getArgPushUrl(arguments) != null) {
            this.config.setPush(true);
            this.config.setPushRemoteRepo(LauncherUtils.getArgPushUrl(arguments));
        }
        this.config.setCreatePR(LauncherUtils.getArgCreatePR(arguments));

        // we fork if we need to create a PR or if we need to notify
        if (this.config.isCreatePR() || (this.config.getSmtpServer() != null && !this.config.getSmtpServer().isEmpty() && this.config.getNotifyTo() != null && this.config.getNotifyTo().length > 0)) {
            this.config.setFork(true);
        }

        this.config.setRepairTools(new HashSet<>(Arrays.asList(arguments.getStringArray("repairTools"))));
        this.config.setFeedbackTools(new HashSet<>(Arrays.asList(arguments.getStringArray("feedbackTools"))));

    }

    private void initSerializerEngines() {
        this.engines = new ArrayList<>();

        List<SerializerEngine> fileSerializerEngines = LauncherUtils.initFileSerializerEngines(LOGGER);
        this.engines.addAll(fileSerializerEngines);

        SerializerEngine mongoDBSerializerEngine = LauncherUtils.initMongoDBSerializerEngine(LOGGER);
        if (mongoDBSerializerEngine != null) {
            this.engines.add(mongoDBSerializerEngine);
        }
    }

    private void initNotifiers() {
        if (this.config.isNotifyEndProcess()) {
            List<NotifierEngine> notifierEngines = LauncherUtils.initNotifierEngines(LOGGER);
            this.endProcessNotifier = new EndProcessNotifier(notifierEngines, LauncherType.DOCKERPOOL.name().toLowerCase()+" (runid: "+this.config.getRunId()+")");
        }
    }

    private List<TravisInputBuild> readListOfBuildIds() {
        List<TravisInputBuild> result = new ArrayList<>();

        File inputFile = new File(this.config.getInputPath());

        try {
            BufferedReader reader = new BufferedReader(new FileReader(inputFile));
            while (reader.ready()) {
                String line = reader.readLine().trim();
                if (!line.isEmpty()) {
                    String[] buildIds = line.split(Utils.COMMA+"");
                    if (buildIds.length > 0) {
                        long buggyBuildId = Long.parseLong(buildIds[0]);
                        if (this.config.getLauncherMode() == LauncherMode.BEARS) {
                            if (buildIds.length > 1) {
                                long patchedBuildId = Long.parseLong(buildIds[1]);
                                result.add(new TravisInputBuild(buggyBuildId, patchedBuildId));
                            } else {
                                LOGGER.error("The build "+buggyBuildId+" will not be processed because there is no next build for it in the input file.");
                            }
                        } else {
                            result.add(new TravisInputBuild(buggyBuildId));
                        }
                    }
                }
            }

            reader.close();
        } catch (IOException e) {
            throw new RuntimeException("Error while reading build ids from file: "+inputFile.getPath(),e);
        }

        return result;
    }

    private void runPool() throws IOException {
        String runId = this.config.getRunId();
        HardwareInfoSerializer hardwareInfoSerializer = new HardwareInfoSerializer(this.engines, runId, "dockerPool");
        hardwareInfoSerializer.serialize();

        EndProcessSerializer endProcessSerializer = new EndProcessSerializer(this.engines, runId);
        List<TravisInputBuild> buildIds = this.readListOfBuildIds();
        LOGGER.info("Find "+buildIds.size()+" builds to run.");

        endProcessSerializer.setNbBuilds(buildIds.size());

        String imageId = DockerHelper.findDockerImage(this.config.getDockerImageName(), dockerPool.getDockerClient());
        LOGGER.info("Found the following docker image id: "+imageId);

        dockerPool.setDockerOutputDir(this.config.getLogDirectory());
        dockerPool.setRunId(runId);
        dockerPool.setEngines(this.engines);

        //
        // ExecutorService executorService = Executors.newFixedThreadPool(this.config.getNbThreads());

        ExecutorService executorService = Executors.newFixedThreadPool(this.config.getNbThreads());

        for (TravisInputBuild inputBuildId : buildIds) {
            executorService.submit(dockerPool.submitBuild(imageId, inputBuildId));
        }

        executorService.shutdown();
        try {
            if (executorService.awaitTermination(this.config.getGlobalTimeout(), TimeUnit.DAYS)) {
                LOGGER.info("Job finished within time.");
                endProcessSerializer.setStatus("ok");
            } else {
                LOGGER.warn("Timeout launched: the job is running for one day. Force stopped "+ dockerPool.submittedRunnablePipelineContainers.size()+" docker container(s).");
                executorService.shutdownNow();
                this.setStatusForUnexecutedJobs();
                endProcessSerializer.setStatus("timeout");
            }
        } catch (InterruptedException e) {
            LOGGER.error("Error while await termination. Force stopped "+ dockerPool.submittedRunnablePipelineContainers.size()+" docker container(s).", e);
            executorService.shutdownNow();
            this.setStatusForUnexecutedJobs();
            endProcessSerializer.setStatus("interrupted");
        }

        dockerPool.getDockerClient().close();
        endProcessSerializer.serialize();
        if (this.endProcessNotifier != null) {
            this.endProcessNotifier.notifyEnd();
        }
    }

    private void setStatusForUnexecutedJobs() {
        for (RunnablePipelineContainer runnablePipelineContainer : dockerPool.submittedRunnablePipelineContainers) {
            runnablePipelineContainer.serialize("ABORTED");
        }
    }

    public static void main(String[] args) throws Exception {
        BuildAnalyzerLauncher buildAnalyzerLauncher = new BuildAnalyzerLauncher(args);
        buildAnalyzerLauncher.runPool();
    }

}
