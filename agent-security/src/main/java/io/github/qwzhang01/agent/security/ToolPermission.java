package io.github.qwzhang01.agent.security;

/**
 * Tool permission level (Stage 9 D2).
 * <p>
 * Three tiers - intentionally NOT fine-grained RBAC. The goal is to understand
 * the core governance mechanism (permission + approval + audit + injection defense),
 * not to build an enterprise IAM. RBAC is Stage 15 (Enterprise Profile).
 *
 * <ul>
 *   <li>{@link #AUTO} - safe tools, model can call anytime (get_time, echo, search)</li>
 *   <li>{@link #REQUIRES_APPROVAL} - dangerous tools, need human confirmation first
 *       (delete_file, send_email, execute_command)</li>
 *   <li>{@link #DENY} - forbidden under any circumstance</li>
 * </ul>
 */
public enum ToolPermission {
    /**
     * Automatically executed without human confirmation.
     */
    AUTO,
    /**
     * Requires human approval before execution.
     */
    REQUIRES_APPROVAL,
    /**
     * Denied under all circumstances. The tool call is rejected and never reaches the tool.
     */
    DENY
}
