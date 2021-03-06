/**
 * Copyright 2020 SkillTree
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package skills.services.events.pointsAndAchievements

import callStack.profiler.Profile
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import skills.services.LevelDefinitionStorageService
import skills.services.events.CompletionItem
import skills.services.events.SkillDate
import skills.storage.model.LevelDefInterface
import skills.storage.model.SkillDef
import skills.storage.model.SkillRelDef
import skills.storage.model.UserAchievement
import skills.storage.model.UserPoints
import skills.storage.repos.SkillEventsSupportRepo

@Slf4j
@CompileStatic
class PointsAndAchievementsBuilder {
    String userId
    String projectId
    String skillId
    Integer skillRefId
    LoadedData loadedData
    int pointIncrement
    SkillDate incomingSkillDate
    LevelDefinitionStorageService levelDefService
    SkillEventsSupportRepo skillEventsSupportRepo


    class PointsAndAchievementsResult {
        DataToSave dataToSave
        List<CompletionItem> completionItems
    }

    DataToSave dataToSave
    List<CompletionItem> completionItems = []

    @Profile
    PointsAndAchievementsResult  build() {
        dataToSave = new DataToSave(pointIncrement: pointIncrement)

        // any parent that exist must get points added
        dataToSave.toAddPointsTo.addAll(loadedData.tinyUserPoints)

        // create user points for this skill if it doesn't already exist
        dataToSave.toSave.addAll(createUserPointsIfNeeded(skillRefId, skillId))

        loadedData.parentDefs.each { SkillEventsSupportRepo.TinySkillDef parentSkillDef ->
            // if needed, create user points for parents
            dataToSave.toSave.addAll(createUserPointsIfNeeded(parentSkillDef.id, parentSkillDef.skillId))
            handleSubjectAchievement(parentSkillDef)
        }

        // if needed, created user points for project
        dataToSave.toSave.addAll(createUserPointsIfNeeded(null, null))

        handleOverallAchievement()

        PointsAndAchievementsResult res = new PointsAndAchievementsResult(dataToSave: dataToSave, completionItems: completionItems)
        return res
    }


    private void handleOverallAchievement() {
        List<UserAchievement> achievements = checkForAchievements(null, null, loadedData.tinyProjectDef.totalPoints)
        if (achievements) {
            dataToSave.userAchievements.addAll(achievements)
            completionItems.addAll(achievements.collect {
                new CompletionItem(
                        level: it.level,
                        name: "OVERALL",
                        id: "OVERALL",
                        type: CompletionItem.CompletionItemType.Overall)
            })
        }
    }

    private void handleSubjectAchievement(SkillEventsSupportRepo.TinySkillDef skillDef) {
        if (skillDef.type == SkillDef.ContainerType.Subject) {
            List<UserAchievement> achievements = checkForAchievements(skillDef.skillId, skillDef.id, skillDef.totalPoints)
            if (achievements) {
                dataToSave.userAchievements.addAll(achievements)
                completionItems.addAll(achievements.collect {
                    new CompletionItem(
                            level: it.level, name: skillDef.name,
                            id: skillDef.skillId,
                            type: CompletionItem.CompletionItemType.Subject)
                })
            }
        }
    }

    @Profile
    private  List<UserAchievement> checkForAchievements(String skillId, Integer skillRefId, Integer totalPoints) {
        List<UserAchievement> res

        SkillEventsSupportRepo.TinyUserPoints existingUserPoints = loadedData.getTotalUserPoints(skillRefId)

        List<LevelDefInterface> levelDefs = loadedData.levels.findAll({ it.skillRefId == skillRefId })
        int currentScore = existingUserPoints ? existingUserPoints.points + pointIncrement : pointIncrement
        LevelDefinitionStorageService.LevelInfo levelInfo = levelDefService.getLevelInfo(projectId, levelDefs, totalPoints, currentScore)

        // first achieved level is 1, level 0 should not be documented
        if (levelInfo.level > 0) {
            List<SkillEventsSupportRepo.TinyUserAchievement> userAchievedLevels = loadedData.getUserAchievements(skillRefId)
            Integer maxAchieved = userAchievedLevels.collect({it.level}).max()
            maxAchieved = maxAchieved ?: 0
            // handle an edge case where user achieves multiple levels via one event
            if (levelInfo.level > maxAchieved) {
                Date achievedOn = getAchievedOnDate(skillRefId)
                res = (maxAchieved+1..levelInfo.level).collect {
                    UserAchievement achievement = new UserAchievement(userId: userId.toLowerCase(), projectId: projectId, skillId: skillId, skillRefId: skillRefId,
                            level: it, pointsWhenAchieved: currentScore, achievedOn: achievedOn)
                    log.debug("Achieved new level [{}]", achievement)
                    return achievement
                }
            }
        }

        return res
    }

    @Profile
    private Date getAchievedOnDate(Integer skillRefId) {
        Date achievedOn = incomingSkillDate.date
        // this work is only performed if the date was provided for the event that caused an achievement to happen;
        // with that said the provided date may not be the latest date of all of the events that contributed to this achievement
        if (incomingSkillDate.isProvided) {
            // get the date of the latest event
            achievedOn = skillRefId ?
                    skillEventsSupportRepo.getUserPerformedSkillLatestDate(userId.toLowerCase(), projectId, skillRefId, SkillRelDef.RelationshipType.RuleSetDefinition) :
                    skillEventsSupportRepo.getUserPerformedSkillLatestDate(userId.toLowerCase(), projectId)

            if (!achievedOn || incomingSkillDate.date.after(achievedOn)) {
                achievedOn = incomingSkillDate.date
            }
        }
        return achievedOn
    }


    private List<UserPoints> createUserPointsIfNeeded(Integer skillRefId, String skillId) {
        List<UserPoints> toSave = []
        List<SkillEventsSupportRepo.TinyUserPoints> myExistingPoints = loadedData.getUserPoints(skillRefId)

        // add overall user points if it's the first time
        if (!myExistingPoints) {
            toSave << constructUserPoints(userId, projectId, skillRefId, skillId, null, pointIncrement)
        }

        // add user points if a record doesn't exist for that day already
        Date incomingDay = new Date(incomingSkillDate.date.time).clearTime()
        if (!myExistingPoints?.find { it.getDay() == incomingDay }) {
            toSave << constructUserPoints(userId, projectId, skillRefId, skillId, incomingDay, pointIncrement)
        }

        return toSave
    }


    private UserPoints constructUserPoints(String userId, String projectId, Integer skillRefId, String skillId, Date day, Integer pointIncrement) {
        return new UserPoints(
                userId: userId.toLowerCase(),
                projectId: projectId,
                skillId: skillId,
                skillRefId: skillRefId,
                points: pointIncrement,
                day: day)
    }

}


