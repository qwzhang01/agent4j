package io.github.qwzhang01.agent.product.definition;

import io.github.qwzhang01.agent.product.ProductContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Semantic validation of an {@link AgentDefinition} against a {@link ProductContext}
 * (Stage 13 M13.1).
 * <p>
 * Two validation layers:
 * <ol>
 *   <li>Structure (definition-internal): required sections, value ranges, mutual exclusion</li>
 *   <li>References (D1 "names -&gt; registry"): every name must resolve in the context,
 *       and reference errors list what IS available (typo-friendly)</li>
 * </ol>
 * Returns ALL errors in one pass so the author fixes everything at once. Does not
 * throw - callers decide (the bootstrapper throws; interactive tooling might not).
 */
public final class DefinitionValidator {

    /**
     * Validate a definition.
     *
     * @param def parsed definition
     * @param ctx product context providing the name registries
     * @return all validation errors, empty list = valid
     */
    public List<ValidationError> validate(AgentDefinition def, ProductContext ctx) {
        List<ValidationError> errors = new ArrayList<>();
        AgentDefinition.Spec spec = def.spec();

        validatePersona(spec.persona(), ctx, errors);
        validateModel(spec.model(), ctx, errors);
        validateTools(spec.tools(), ctx, errors);
        validateMemory(spec.memory(), ctx, errors);
        validateWorkflow(spec.workflow(), ctx, errors);
        validateAmbient(spec.ambient(), errors);

        return errors;
    }

    // --------------------------------------------
    // Section validators
    // --------------------------------------------

    private void validatePersona(AgentDefinition.Persona persona, ProductContext ctx,
                                 List<ValidationError> errors) {
        if (persona == null) {
            errors.add(new ValidationError("spec.persona",
                    "must declare exactly one of systemPrompt or promptRef"));
            return;
        }
        boolean hasInline = persona.systemPrompt() != null && !persona.systemPrompt().isBlank();
        boolean hasRef = persona.promptRef() != null;
        if (hasInline && hasRef) {
            errors.add(new ValidationError("spec.persona",
                    "systemPrompt and promptRef are mutually exclusive - pick one"));
        } else if (!hasInline && !hasRef) {
            errors.add(new ValidationError("spec.persona",
                    "must declare exactly one of systemPrompt or promptRef"));
        }

        if (hasRef) {
            PromptRef ref = persona.promptRef();
            if (ref.channel() != null
                    && !"stable".equals(ref.channel()) && !"canary".equals(ref.channel())) {
                errors.add(new ValidationError("spec.persona.promptRef.channel",
                        "must be 'stable' or 'canary', got: " + ref.channel()));
            }
            var manager = ctx.promptManager();
            if (manager.isEmpty()) {
                errors.add(new ValidationError("spec.persona.promptRef",
                        "the platform has no PromptManager registered "
                                + "(inline systemPrompt needs none)"));
            } else if (manager.get().resolve(ref.name(), null, null).isEmpty()) {
                errors.add(new ValidationError("spec.persona.promptRef.name",
                        "'" + ref.name() + "' is not a published prompt, available: "
                                + manager.get().promptNames()));
            }
        }

        if (persona.temperature() != null) {
            double t = persona.temperature();
            if (t < 0 || t > 2) {
                errors.add(new ValidationError("spec.persona.temperature",
                        "must be within [0, 2], got " + t));
            }
        }
    }

    private void validateModel(AgentDefinition.Model model, ProductContext ctx,
                               List<ValidationError> errors) {
        if (model == null || model.provider() == null || model.provider().isBlank()) {
            errors.add(new ValidationError("spec.model.provider",
                    "must not be blank (registered model name)"));
            return;
        }
        if (ctx.model(model.provider()).isEmpty()) {
            errors.add(new ValidationError("spec.model.provider",
                    "'" + model.provider() + "' is not a registered model, available: "
                            + ctx.modelNames()));
        }
        if (model.fallback() != null && ctx.model(model.fallback()).isEmpty()) {
            errors.add(new ValidationError("spec.model.fallback",
                    "'" + model.fallback() + "' is not a registered model, available: "
                            + ctx.modelNames()));
        }
    }

    private void validateTools(List<AgentDefinition.ToolRef> tools, ProductContext ctx,
                               List<ValidationError> errors) {
        if (tools == null) {
            return; // no tools is legal
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tools.size(); i++) {
            AgentDefinition.ToolRef toolRef = tools.get(i);
            String basePath = "spec.tools[" + i + "]";
            if (toolRef == null) {
                errors.add(new ValidationError(basePath, "must be 'ref' or 'http'"));
                continue;
            }
            if (toolRef.ref() != null) {
                validateToolRef(toolRef.ref(), basePath + ".ref", ctx, seen, errors);
            } else {
                validateHttpDecl(toolRef.http(), basePath + ".http", seen, errors);
            }
        }
    }

    private void validateToolRef(String ref, String path, ProductContext ctx,
                                 Set<String> seen, List<ValidationError> errors) {
        if (ref == null || ref.isBlank()) {
            errors.add(new ValidationError(path, "must not be blank"));
            return;
        }
        if (!seen.add(ref)) {
            errors.add(new ValidationError(path, "duplicate tool name '" + ref + "'"));
        }
        if (ctx.tool(ref).isEmpty()) {
            errors.add(new ValidationError(path,
                    "'" + ref + "' is not a registered tool, available: " + ctx.toolNames()));
        }
    }

    private void validateHttpDecl(HttpApiDecl decl, String path, Set<String> seen,
                                  List<ValidationError> errors) {
        if (decl.name() == null || decl.name().isBlank()) {
            errors.add(new ValidationError(path + ".name", "must not be blank"));
            return; // without a name the rest of the checks are noise
        }
        if (!seen.add(decl.name())) {
            errors.add(new ValidationError(path + ".name",
                    "duplicate tool name '" + decl.name() + "' (refs and http tools share one namespace)"));
        }
        if (decl.description() == null || decl.description().isBlank()) {
            errors.add(new ValidationError(path + ".description",
                    "must not be blank (the model reads it to decide when to call the tool)"));
        }
        if (decl.endpoint() == null || !(decl.endpoint().startsWith("http://")
                || decl.endpoint().startsWith("https://"))) {
            errors.add(new ValidationError(path + ".endpoint",
                    "must be an http(s) URL, got: " + decl.endpoint()));
        }
        if (decl.method() != null
                && !List.of("GET", "POST", "PUT", "DELETE").contains(decl.method())) {
            errors.add(new ValidationError(path + ".method",
                    "must be one of GET/POST/PUT/DELETE, got: " + decl.method()));
        }
        decl.params().forEach((name, param) -> {
            if (param.in() != null && !List.of("query", "body", "path").contains(param.in())) {
                errors.add(new ValidationError(path + ".params." + name + ".in",
                        "must be one of query/body/path, got: " + param.in()));
            }
            if ("body".equals(param.in())
                    && decl.method() != null && "GET".equals(decl.method())) {
                errors.add(new ValidationError(path + ".params." + name + ".in",
                        "body params are not allowed with method GET"));
            }
        });
        if (decl.timeoutSeconds() != null && decl.timeoutSeconds() <= 0) {
            errors.add(new ValidationError(path + ".timeoutSeconds",
                    "must be positive, got " + decl.timeoutSeconds()));
        }
        if (decl.response() != null && decl.response().extract() != null
                && !decl.response().extract().startsWith("$.")) {
            errors.add(new ValidationError(path + ".response.extract",
                    "must be a dot path starting with '$.', got: " + decl.response().extract()));
        }
        if (decl.auth() != null) {
            if (!"bearer".equals(decl.auth().type())) {
                errors.add(new ValidationError(path + ".auth.type",
                        "must be 'bearer' (v1), got: " + decl.auth().type()));
            }
            if (decl.auth().token() == null || decl.auth().token().isBlank()) {
                errors.add(new ValidationError(path + ".auth.token",
                        "must not be blank (use ${env:NAME} for secrets)"));
            }
        }
    }

    private void validateMemory(AgentDefinition.Memory memory, ProductContext ctx,
                                List<ValidationError> errors) {
        if (memory == null) {
            return; // passthrough (Stage 1-7 behavior)
        }
        boolean hasShortTerm = memory.shortTerm() != null;
        boolean hasNamed = memory.contextBuilder() != null;
        if (hasShortTerm && hasNamed) {
            errors.add(new ValidationError("spec.memory",
                    "shortTerm and contextBuilder are mutually exclusive - pick one"));
        }
        if (hasShortTerm) {
            AgentDefinition.Memory.ShortTerm shortTerm = memory.shortTerm();
            if (!"window".equals(shortTerm.strategy())) {
                errors.add(new ValidationError("spec.memory.shortTerm.strategy",
                        "unknown strategy '" + shortTerm.strategy() + "', supported: [window]"));
            }
            if (shortTerm.maxMessages() == null || shortTerm.maxMessages() <= 0) {
                errors.add(new ValidationError("spec.memory.shortTerm.maxMessages",
                        "must be a positive integer"));
            }
        }
        if (hasNamed && ctx.contextBuilder(memory.contextBuilder()).isEmpty()) {
            errors.add(new ValidationError("spec.memory.contextBuilder",
                    "'" + memory.contextBuilder() + "' is not a registered context builder, "
                            + "available: " + ctx.contextBuilderNames()));
        }
    }

    // --------------------------------------------
    // M13.5: workflow reference + ambient declarations
    // --------------------------------------------

    private void validateWorkflow(String workflow, ProductContext ctx,
                                   List<ValidationError> errors) {
        if (workflow == null) {
            return;
        }
        if (ctx.workflow(workflow).isEmpty()) {
            errors.add(new ValidationError("spec.workflow",
                    "'" + workflow + "' is not a registered workflow, available: "
                            + ctx.workflowNames()));
        }
    }

    private void validateAmbient(List<AgentDefinition.AmbientDecl> ambient,
                                 List<ValidationError> errors) {
        if (ambient == null) {
            return;
        }
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (int i = 0; i < ambient.size(); i++) {
            AgentDefinition.AmbientDecl decl = ambient.get(i);
            String path = "spec.ambient[" + i + "]";
            if (decl.instructionId() == null || decl.instructionId().isBlank()) {
                errors.add(new ValidationError(path + ".instructionId", "must not be blank"));
            } else if (!seenIds.add(decl.instructionId())) {
                errors.add(new ValidationError(path + ".instructionId",
                        "duplicate instruction id '" + decl.instructionId() + "'"));
            }
            if (decl.description() == null || decl.description().isBlank()) {
                errors.add(new ValidationError(path + ".description", "must not be blank"));
            }
            AgentDefinition.AmbientDecl.TriggerDecl trigger = decl.trigger();
            boolean hasEvent = trigger != null && trigger.onEvent() != null && !trigger.onEvent().isBlank();
            boolean hasSchedule = trigger != null && trigger.schedule() != null && !trigger.schedule().isBlank();
            if (hasEvent == hasSchedule) {
                errors.add(new ValidationError(path + ".trigger",
                        "must declare exactly one of onEvent or schedule"));
            } else if (hasSchedule) {
                try {
                    java.time.Duration.parse(trigger.schedule());
                } catch (Exception e) {
                    errors.add(new ValidationError(path + ".trigger.schedule",
                            "must be an ISO-8601 duration like PT10M, got: " + trigger.schedule()));
                }
            }
            if (decl.importance() == null
                    || !List.of("INFO", "WARN", "CRITICAL").contains(decl.importance())) {
                errors.add(new ValidationError(path + ".importance",
                        "must be one of INFO/WARN/CRITICAL, got: " + decl.importance()));
            }
            if (decl.messageTemplate() == null || decl.messageTemplate().isBlank()) {
                errors.add(new ValidationError(path + ".messageTemplate", "must not be blank"));
            }
        }
    }
}
