package com.wgblackmon.web.service;

import java.util.List;

/**
 * The structured outcome of one prior authorization analysis run.
 *
 * @param determination  APPROVED, DENIED, or NEEDS_MORE_INFO — parsed from the agent's text
 * @param rationale      the agent's narrative explanation (RATIONALE section)
 * @param criteria       per-criterion status list (MET / UNMET / INSUFFICIENT)
 * @param gaps           documentation items the agent identified as missing or incomplete
 * @param appealPoints   bullet points to support an appeal, populated when determination is DENIED
 * @param toolCalls      ordered list of tool invocations captured during the run
 */
public record PriorAuthResult(
        String determination,
        String rationale,
        List<CriterionResult> criteria,
        List<String> gaps,
        List<String> appealPoints,
        List<ToolCallRecord> toolCalls) {

    public boolean hasGaps()         { return gaps != null && !gaps.isEmpty(); }
    public boolean hasCriteria()     { return criteria != null && !criteria.isEmpty(); }
    public boolean hasAppealPoints() { return appealPoints != null && !appealPoints.isEmpty(); }
}
