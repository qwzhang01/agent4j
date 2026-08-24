package io.github.qwzhang01.agent.coding.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.3: gate 2 - prefix matching on argv, fail-closed on everything else.
 */
class CommandWhitelistTest {

    @Test
    @DisplayName("prefix match: rule [mvn,test] allows 'mvn test -q' and exact 'mvn test'")
    void prefixAllowed() {
        CommandWhitelist whitelist = CommandWhitelist.builder()
                .rule("mvn", "test")
                .rule("java")
                .build();

        assertTrue(whitelist.check(List.of("mvn", "test")).allowed());
        assertTrue(whitelist.check(List.of("mvn", "test", "-q")).allowed());
        assertTrue(whitelist.check(List.of("mvn", "test", "-Dtest=AppTest")).allowed());
        assertTrue(whitelist.check(List.of("java")).allowed());
        assertTrue(whitelist.check(List.of("java", "-version")).allowed());
    }

    @Test
    @DisplayName("argv[1] out of scope: rule [mvn,test] denies 'mvn clean'")
    void secondElementOutOfBounds() {
        CommandWhitelist whitelist = CommandWhitelist.builder()
                .rule("mvn", "test")
                .build();

        CommandWhitelist.CheckResult denied = whitelist.check(List.of("mvn", "clean"));
        assertFalse(denied.allowed());
        assertTrue(denied.reason().contains("mvn clean"), denied.reason());
    }

    @Test
    @DisplayName("not in the table: fail-closed with the offending command in the reason")
    void notInTable() {
        CommandWhitelist whitelist = CommandWhitelist.builder()
                .rule("mvn", "test")
                .build();

        CommandWhitelist.CheckResult denied = whitelist.check(List.of("curl", "http://evil.example"));
        assertFalse(denied.allowed());
        assertTrue(denied.reason().contains("curl"), denied.reason());
        assertFalse(whitelist.check(List.of("rm", "-rf", "/")).allowed());
        assertFalse(whitelist.check(List.of("sh", "-c", "anything")).allowed());
    }

    @Test
    @DisplayName("rule longer than argv does not match (a shorter argv is a different command)")
    void ruleLongerThanArgv() {
        CommandWhitelist whitelist = CommandWhitelist.builder()
                .rule("mvn", "test")
                .build();

        assertFalse(whitelist.check(List.of("mvn")).allowed());
    }

    @Test
    @DisplayName("malformed argv: null/empty/blank elements are denied, not crashed on")
    void malformedArgv() {
        CommandWhitelist whitelist = CommandWhitelist.builder()
                .rule("mvn")
                .build();

        assertFalse(whitelist.check(null).allowed());
        assertFalse(whitelist.check(List.of()).allowed());
        assertFalse(whitelist.check(List.of("mvn", " ")).allowed());
        assertFalse(whitelist.check(java.util.Arrays.asList("mvn", null)).allowed());
    }

    @Test
    @DisplayName("rule validation: empty rule / blank elements fail fast at build time")
    void ruleValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandWhitelist.builder().rule().build());
        assertThrows(IllegalArgumentException.class,
                () -> CommandWhitelist.builder().rule(" ", "test").build());
    }

    @Test
    @DisplayName("summary lists the granted prefixes for feedback and audit")
    void summary() {
        CommandWhitelist whitelist = CommandWhitelist.builder()
                .rule("mvn", "test")
                .rule("java")
                .build();

        assertEquals("mvn test | java", whitelist.summary());
        assertEquals(2, whitelist.size());
    }
}
