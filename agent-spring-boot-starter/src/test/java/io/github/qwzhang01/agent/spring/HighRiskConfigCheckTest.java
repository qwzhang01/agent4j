package io.github.qwzhang01.agent.spring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * boot check: findings are graded, SECURE blocks only on the
 * objective contradiction, TEST never blocks.
 */
class HighRiskConfigCheckTest {

    private static AgentProperties props(String autoApprove) {
        AgentProperties p = new AgentProperties();
        p.getApproval().setAutoApprove(Boolean.parseBoolean(autoApprove));
        return p;
    }

    @Test
    void secureWithAutoApproveIsTheHighFinding() {
        HighRiskConfigCheck check = new HighRiskConfigCheck(
                AgentProfile.SECURE, props("true"));
        List<HighRiskConfigCheck.Finding> findings = check.inspect(true, true);
        assertThat(findings).anyMatch(f -> f.level() == HighRiskConfigCheck.Level.HIGH
                && f.component().equals("approval"));
        assertThat(check.shouldBlockBoot(findings)).isTrue();
    }

    @Test
    void secureWithDenyOnAbsenceBootsClean() {
        HighRiskConfigCheck check = new HighRiskConfigCheck(
                AgentProfile.SECURE, props("false"));
        List<HighRiskConfigCheck.Finding> findings = check.inspect(true, true);
        // sandbox+store present, auto-approve off, mock provider: no HIGH
        assertThat(findings).noneMatch(f -> f.level() == HighRiskConfigCheck.Level.HIGH);
        assertThat(check.shouldBlockBoot(findings)).isFalse();
    }

    @Test
    void testProfileNeverBlocksEvenWithAutoApprove() {
        HighRiskConfigCheck check = new HighRiskConfigCheck(
                AgentProfile.TEST, props("true"));
        List<HighRiskConfigCheck.Finding> findings = check.inspect(false, false);
        assertThat(check.shouldBlockBoot(findings)).isFalse();
    }

    @Test
    void unsafeSkipsTheCheckEntirely() {
        HighRiskConfigCheck check = new HighRiskConfigCheck(
                AgentProfile.UNSAFE, props("true"));
        assertThat(check.inspect(false, false)).isEmpty();
        assertThat(check.shouldBlockBoot(List.of())).isFalse();
    }

    @Test
    void missingStoreAndSandboxAreMediumFindings() {
        HighRiskConfigCheck check = new HighRiskConfigCheck(
                AgentProfile.SECURE, props("false"));
        List<HighRiskConfigCheck.Finding> findings = check.inspect(false, false);
        assertThat(findings).anyMatch(f -> f.level() == HighRiskConfigCheck.Level.MEDIUM
                && f.component().equals("store"));
        assertThat(findings).anyMatch(f -> f.level() == HighRiskConfigCheck.Level.MEDIUM
                && f.component().equals("sandbox"));
    }

    @Test
    void blankApiKeyOnOpenAiProviderIsMedium() {
        AgentProperties p = props("false");
        p.getModel().setProvider("openai");
        p.getModel().setApiKey("");
        HighRiskConfigCheck check = new HighRiskConfigCheck(AgentProfile.SECURE, p);
        List<HighRiskConfigCheck.Finding> findings = check.inspect(true, true);
        assertThat(findings).anyMatch(f -> f.component().equals("model")
                && f.level() == HighRiskConfigCheck.Level.MEDIUM);
    }

    @Test
    void renderLogIncludesEveryFinding() {
        HighRiskConfigCheck check = new HighRiskConfigCheck(
                AgentProfile.SECURE, props("true"));
        String rendered = check.renderLog(check.inspect(false, false));
        assertThat(rendered).contains("[HighRiskConfigCheck]");
        assertThat(rendered).contains("approval");
        assertThat(rendered).contains("store");
        assertThat(rendered).contains("sandbox");
    }
}
