package skills.service.skillsManagement

import groovy.time.TimeCategory
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import skills.storage.model.SkillDef
import skills.storage.repos.UserPerformedSkillRepo

@Component
@Slf4j
@CompileStatic
class TimeWindowHelper {

    @Autowired
    UserPerformedSkillRepo performedSkillRepository

    static class TimeWindowRes {
        boolean full
        String msg
    }

    private static TimeWindowRes disabled = new TimeWindowRes(full: false);

    @CompileDynamic
    TimeWindowRes checkTimeWindow(SkillDef skillDefinition, String userId, Date incomingSkillDate) {
        // pointIncrementInterval set to 0 disables time windows and skill events should be applied immediately
        boolean timeWindowDisabled = skillDefinition.pointIncrementInterval <= 0
        if (timeWindowDisabled) {
            return disabled
        }

        // because incoming date may be provided in the past (ex. automated job) then we simply have to make sure that
        // there is no other skill before or after skillDefinition.pointIncrementInterval
        Date checkStartDate
        Date checkEndDate
        use(TimeCategory) {
            checkStartDate = incomingSkillDate - skillDefinition.pointIncrementInterval.minutes
            checkEndDate = incomingSkillDate + skillDefinition.pointIncrementInterval.minutes
        }
        if (log.isDebugEnabled()) {
            log.debug("Looking for [$skillDefinition.skillId] between [$checkStartDate] and [$checkEndDate]")
        }

        Long count = performedSkillRepository.countByUserIdAndProjectIdAndSkillIdAndPerformedOnGreaterThanAndPerformedOnLessThan(
                userId,
                skillDefinition.projectId,
                skillDefinition.skillId,
                checkStartDate,
                checkEndDate
        )
        // a little bit of paranoia to make sure that count is at least 1 before comparing with occurrences
        // this could only happen of course if numMaxOccurrencesIncrementInterval is miconfigured, but hey, happens
        boolean isFull = count > 0 && count >= skillDefinition.numMaxOccurrencesIncrementInterval
        String msg = isFull ? buildMsg(skillDefinition, count) : null
        new TimeWindowRes(full: isFull, msg: msg)
    }

    private String buildMsg(SkillDef skillDef, Long count){
        "This skill was already performed ${count > 1 ? "${count} out of ${count} times " : ""}within the configured time period (within the last ${timeWindowPrettyPrint(skillDef)})".toString()
    }

    private String timeWindowPrettyPrint(SkillDef skillDefinition) {
        int hours = skillDefinition.pointIncrementInterval >= 60 ? (int) (skillDefinition.pointIncrementInterval / 60) : 0
        int minutes = skillDefinition.pointIncrementInterval >= 60 ? (int) (skillDefinition.pointIncrementInterval % 60) : skillDefinition.pointIncrementInterval
        StringBuilder res = new StringBuilder()

        if ( hours > 0) {
            res.append(hours)
            res.append(" hour")
            if (hours > 1) {
                res.append("s")
            }
        }

        if (minutes > 0) {
            if ( hours > 0 ){
                res.append(" ")
            }
            res.append(minutes)
            res.append(" minute")
            if (minutes > 1) {
                res.append("s")
            }
        }

        res.toString()
    }
}
