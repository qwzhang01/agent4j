package io.github.qwzhang01.agent.workflow.nodes;

/**
 * How a ParallelNode converges its branches.
 * <p>
 * ALL_OF - wait for every branch, aggregate outputs into a
 * Map&lt;branchName, lastOutput&gt; (fork-join).
 * ANY_OF - take the first completed branch's output;
 * losing branches keep running and their blackboard
 * writes still land (documented side effect).
 */
public enum JoinPolicy {
    ALL_OF,
    ANY_OF
}
