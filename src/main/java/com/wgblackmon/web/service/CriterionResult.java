package com.wgblackmon.web.service;

/**
 * One coverage criterion evaluated during a prior authorization run.
 *
 * @param criterion  short description of the policy requirement
 * @param status     MET | UNMET | INSUFFICIENT
 * @param evidence   the patient evidence (or lack thereof) cited by the agent
 */
public record CriterionResult(String criterion, String status, String evidence) {

    public boolean isMet()          { return "MET".equalsIgnoreCase(status); }
    public boolean isUnmet()        { return "UNMET".equalsIgnoreCase(status); }
    public boolean isInsufficient() { return "INSUFFICIENT".equalsIgnoreCase(status); }
}
