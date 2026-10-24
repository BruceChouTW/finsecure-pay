import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JenkinsFailureDemoTest {

    @Test
    void intentionalFailureForPipelineVerification() {
        assertTrue(
            false,
            "Intentional failure to verify Jenkins blocks downstream stages"
        );
    }
}
