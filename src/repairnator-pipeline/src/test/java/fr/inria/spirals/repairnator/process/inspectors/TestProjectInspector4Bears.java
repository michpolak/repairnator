package fr.inria.spirals.repairnator.process.inspectors;

import ch.qos.logback.classic.Level;
import fr.inria.jtravis.entities.Build;
import fr.inria.spirals.repairnator.BuildToBeInspected;
import fr.inria.spirals.repairnator.utils.Utils;
import fr.inria.spirals.repairnator.config.RepairnatorConfig;
import fr.inria.spirals.repairnator.notifier.AbstractNotifier;
import fr.inria.spirals.repairnator.notifier.BugAndFixerBuildsNotifier;
import fr.inria.spirals.repairnator.notifier.engines.NotifierEngine;
import fr.inria.spirals.repairnator.process.files.FileHelper;
import fr.inria.spirals.repairnator.process.step.AbstractStep;
import fr.inria.spirals.repairnator.process.step.StepStatus;
import fr.inria.spirals.repairnator.process.step.push.PushProcessEnd;
import fr.inria.spirals.repairnator.process.utils4tests.Utils4Tests;
import fr.inria.spirals.repairnator.serializer.AbstractDataSerializer;
import fr.inria.spirals.repairnator.serializer.engines.SerializerEngine;
import fr.inria.spirals.repairnator.states.LauncherMode;
import fr.inria.spirals.repairnator.states.PipelineState;
import fr.inria.spirals.repairnator.states.PushState;
import fr.inria.spirals.repairnator.states.ScannedBuildStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.hamcrest.core.Is;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Created by urli on 24/04/2017.
 */
public class TestProjectInspector4Bears {

    private File tmpDir;

    private SerializerEngine serializerEngine;
    private List<AbstractDataSerializer> serializers;
    private NotifierEngine notifierEngine;
    private List<AbstractNotifier> notifiers;

    @Before
    public void setUp() {
        /**
        RepairnatorConfig config = RepairnatorConfig.getInstance();
        config.setLauncherMode(LauncherMode.BEARS);
        config.setZ3solverPath(Utils4Tests.getZ3SolverPath());
        config.setPush(true);
        config.setPushRemoteRepo("");
        config.setGithubUserEmail("noreply@github.com");
        config.setGithubUserName("repairnator");
        config.setJTravisEndpoint("https://api.travis-ci.com");
        Utils.setLoggersLevel(Level.ERROR);

        serializerEngine = mock(SerializerEngine.class);
        List<SerializerEngine> serializerEngines = new ArrayList<>();
        serializerEngines.add(serializerEngine);
        serializers = new ArrayList<>();

        notifierEngine = mock(NotifierEngine.class);
        List<NotifierEngine> notifierEngines = new ArrayList<>();
        notifierEngines.add(notifierEngine);
        notifiers = new ArrayList<>();
        notifiers.add(new BugAndFixerBuildsNotifier(notifierEngines));
         **/
    }

    @After
    public void tearDown() throws IOException {
        RepairnatorConfig.deleteInstance();
        FileHelper.deleteFile(tmpDir);
    }

    @Test
    @Ignore
    //FIXME: We can't rely on repairnator/failing project to get builds
    public void testFailingPassingProject() throws IOException, GitAPIException {
        long buildIdPassing = 226012005; // https://travis-ci.com/github/repairnator/TestingProject/builds/226012005
        long buildIdFailing = 225936611; // https://travis-ci.com/github/repairnator/TestingProject/builds/225936611

        tmpDir = Files.createTempDirectory("test_bears1").toFile();

        Build passingBuild = this.checkBuildAndReturn(buildIdPassing, false);
        Build failingBuild = this.checkBuildAndReturn(buildIdFailing, false);

        BuildToBeInspected buildToBeInspected = new BuildToBeInspected(failingBuild, passingBuild, ScannedBuildStatus.FAILING_AND_PASSING, "test");

        ProjectInspector4Bears inspector = (ProjectInspector4Bears)InspectorFactory.getBearsInspector(buildToBeInspected, tmpDir.getAbsolutePath(), notifiers);
        inspector.setSerializers(this.serializers);
        inspector.run();

        JobStatus jobStatus = inspector.getJobStatus();

        List<StepStatus> stepStatusList = inspector.getJobStatus().getStepStatuses();

        Map<Class<? extends AbstractStep>, StepStatus.StatusKind> expectedStatuses = new HashMap<>();
        expectedStatuses.put(PushProcessEnd.class, StepStatus.StatusKind.SKIPPED); // no remote info provided

        this.checkStepStatus(stepStatusList, expectedStatuses);

        assertThat(jobStatus.getLastPushState(), is(PushState.REPO_NOT_PUSHED));
        assertThat(inspector.isBug(), is(true));
        assertThat(inspector.getBugType(), is("BUG_FAILING_PASSING"));
        assertThat(jobStatus.getFailureLocations().size(), is(1));
        assertThat(jobStatus.getFailureNames().size(), is(1));

        String finalStatus = AbstractDataSerializer.getPrettyPrintState(inspector);
        assertThat(finalStatus, is(PipelineState.BUG_FAILING_PASSING.name()));

        verify(notifierEngine, times(1)).notify(anyString(), anyString());

        Git gitDir = Git.open(new File(inspector.getRepoToPushLocalPath()));
        Iterable<RevCommit> logs = gitDir.log().call();

        Iterator<RevCommit> iterator = logs.iterator();
        assertThat(iterator.hasNext(), is(true));

        RevCommit commit = iterator.next();
        assertTrue(commit.getShortMessage().contains("End of the bug and patch reproduction process"));

        commit = iterator.next();
        assertTrue(commit.getShortMessage().contains("Human patch"));

        commit = iterator.next();
        assertTrue(commit.getShortMessage().contains("Bug commit"));

        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    @Ignore
    //FIXME: We can't rely on repairnator/failing project to get builds
    public void testPassingPassingProject() throws IOException, GitAPIException {
        long buildIdPassing = 226012099; // https://travis-ci.com/github/repairnator/TestingProject/builds/226012099
        long buildIdPreviousPassing = 226012117; // https://travis-ci.com/github/repairnator/TestingProject/builds/226012117

        tmpDir = Files.createTempDirectory("test_bears2").toFile();

        Build passingBuild = this.checkBuildAndReturn(buildIdPassing, false);
        Build previousPassingBuild = this.checkBuildAndReturn(buildIdPreviousPassing, false);

        BuildToBeInspected buildToBeInspected = new BuildToBeInspected(previousPassingBuild, passingBuild, ScannedBuildStatus.PASSING_AND_PASSING_WITH_TEST_CHANGES, "test");

        ProjectInspector4Bears inspector = (ProjectInspector4Bears)InspectorFactory.getBearsInspector(buildToBeInspected, tmpDir.getAbsolutePath(), notifiers);
        inspector.setSerializers(this.serializers);
        inspector.run();

        JobStatus jobStatus = inspector.getJobStatus();
        List<StepStatus> stepStatusList = inspector.getJobStatus().getStepStatuses();

        Map<Class<? extends AbstractStep>, StepStatus.StatusKind> expectedStatuses = new HashMap<>();
        expectedStatuses.put(PushProcessEnd.class, StepStatus.StatusKind.SKIPPED); // no remote info provided

        this.checkStepStatus(stepStatusList, expectedStatuses);

        assertThat(jobStatus.getLastPushState(), is(PushState.REPO_NOT_PUSHED));
        assertThat(inspector.isBug(), is(true));
        assertThat(inspector.getBugType(), is("BUG_PASSING_PASSING"));
        assertThat(jobStatus.getFailureLocations().size(), is(1));
        assertThat(jobStatus.getFailureNames().size(), is(1));

        String finalStatus = AbstractDataSerializer.getPrettyPrintState(inspector);
        assertThat(finalStatus, is(PipelineState.BUG_PASSING_PASSING.name()));

        verify(notifierEngine, times(1)).notify(anyString(), anyString());

        Git gitDir = Git.open(new File(inspector.getRepoToPushLocalPath()));
        Iterable<RevCommit> logs = gitDir.log().call();

        Iterator<RevCommit> iterator = logs.iterator();
        assertThat(iterator.hasNext(), is(true));

        RevCommit commit = iterator.next();
        assertTrue(commit.getShortMessage().contains("End of the bug and patch reproduction process"));

        commit = iterator.next();
        assertTrue(commit.getShortMessage().contains("Human patch"));

        commit = iterator.next();
        assertTrue(commit.getShortMessage().contains("Changes in the tests"));

        commit = iterator.next();
        assertTrue(commit.getShortMessage().contains("Bug commit"));

        assertFalse(iterator.hasNext());
    }

    private Build checkBuildAndReturn(long buildId, boolean isPR) {
        Optional<Build> optionalBuild = RepairnatorConfig.getInstance().getJTravis().build().fromId(buildId);
        assertTrue(optionalBuild.isPresent());

        Build build = optionalBuild.get();
        assertThat(build, notNullValue());
        assertThat(buildId, Is.is(build.getId()));
        assertThat(build.isPullRequest(), Is.is(isPR));

        return build;
    }

    private void checkStepStatus(List<StepStatus> statuses, Map<Class<? extends AbstractStep>,StepStatus.StatusKind> expectedValues) {
        for (StepStatus stepStatus : statuses) {
            if (!expectedValues.containsKey(stepStatus.getStep().getClass())) {
                assertThat("Step failing: "+stepStatus, stepStatus.isSuccess(), is(true));
            } else {
                StepStatus.StatusKind expectedStatus = expectedValues.get(stepStatus.getStep().getClass());
                assertThat("Status was not as expected" + stepStatus, stepStatus.getStatus(), is(expectedStatus));
                expectedValues.remove(stepStatus.getStep().getClass());
            }
        }

        assertThat(expectedValues.isEmpty(), is(true));
    }
}
