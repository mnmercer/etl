package com.linkedpipes.plugin.transformer.jsonldtofile;

import com.linkedpipes.etl.test.suite.TestConfigurationDescription;
import org.junit.jupiter.api.Test;

public class ValidationTest {

    @Test
    public void verifyConfigurationDescription() throws Exception {
        final TestConfigurationDescription test =
                new TestConfigurationDescription();
        test.test(JsonLdToRdfChunkedConfiguration.class);
    }

}
