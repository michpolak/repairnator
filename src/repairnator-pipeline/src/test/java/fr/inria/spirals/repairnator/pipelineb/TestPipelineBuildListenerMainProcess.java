package fr.inria.spirals.repairnator.pipeline;

import fr.inria.spirals.repairnator.config.RepairnatorConfig;
import fr.inria.spirals.repairnator.pipeline.listener.PipelineBuildListenerMainProcess;

import org.apache.activemq.command.ActiveMQBytesMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.util.ByteSequence;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.jms.MessageNotWriteableException;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class TestPipelineBuildListenerMainProcess {

    @Rule
    public TemporaryFolder workspaceFolder = new TemporaryFolder();

    @Rule
    public TemporaryFolder outputFolder = new TemporaryFolder();

    @After
    public void tearDown() throws IOException {
        RepairnatorConfig.deleteInstance();
    }

    @Test
    public void testMessageExtractor() {
        PipelineBuildListenerMainProcess buildListener = (PipelineBuildListenerMainProcess) MainProcessFactory.getPipelineListenerMainProcess(new String[]{
                "--workspace", workspaceFolder.getRoot().getAbsolutePath(),
                "--output", outputFolder.getRoot().getAbsolutePath()
        });
        ActiveMQTextMessage textMessage = new ActiveMQTextMessage();

        try {
            textMessage.setText("1");
        } catch (MessageNotWriteableException e) {
            e.printStackTrace();
        }
        // a plain-text message which only contains a build id
        assertEquals(1, buildListener.extractBuiltId(textMessage));

        try {
            textMessage.setText("{\"buildId\":\"2\",\"CI\":\"travis-ci.com\"}");
        } catch (MessageNotWriteableException e) {
            e.printStackTrace();
        }
        // a plain-text message which is in json
        assertEquals(2, buildListener.extractBuiltId(textMessage));

        ActiveMQBytesMessage bytesMessage = new ActiveMQBytesMessage();
        bytesMessage.setContent(new ByteSequence("3".getBytes()));
        bytesMessage.setReadOnlyBody(true);
        // a bytes message which only contains a build id
        assertEquals(3, buildListener.extractBuiltId(bytesMessage));

        ActiveMQBytesMessage bytesMessageInJson = new ActiveMQBytesMessage();
        bytesMessageInJson.setContent(new ByteSequence("{\"buildId\":\"4\",\"CI\":\"travis-ci.com\"}".getBytes()));
        bytesMessageInJson.setReadOnlyBody(true);
        // a bytes message whose content is formatted in json
        assertEquals(4, buildListener.extractBuiltId(bytesMessageInJson));
    }
}
