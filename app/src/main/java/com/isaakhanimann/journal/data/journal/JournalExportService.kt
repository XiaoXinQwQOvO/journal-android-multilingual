/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.journal

import android.content.Context
import com.isaakhanimann.journal.data.room.experiences.ExperienceRepository
import com.isaakhanimann.journal.ui.tabs.settings.AvatarUtil
import com.isaakhanimann.journal.ui.tabs.settings.CustomUnitSerializable
import com.isaakhanimann.journal.ui.tabs.settings.ExperienceSerializable
import com.isaakhanimann.journal.ui.tabs.settings.IngestionSerializable
import com.isaakhanimann.journal.ui.tabs.settings.JournalExport
import com.isaakhanimann.journal.ui.tabs.settings.LocationSerializable
import com.isaakhanimann.journal.ui.tabs.settings.RatingSerializable
import com.isaakhanimann.journal.ui.tabs.settings.TimedNoteSerializable
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileOutputStream
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class JournalExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val experienceRepository: ExperienceRepository,
    private val userPreferences: UserPreferences
) {
    suspend fun build(): JournalExport = withContext(Dispatchers.IO) {
        val experiencesWithIngestionsAndRatings =
            experienceRepository.getAllExperiencesWithIngestionsTimedNotesAndRatingsSorted()
        val experiencesSerializable = experiencesWithIngestionsAndRatings.map {
            val location = it.experience.location
            ExperienceSerializable(
                title = it.experience.title,
                text = it.experience.text,
                creationDate = it.experience.creationDate,
                sortDate = it.experience.sortDate,
                isFavorite = it.experience.isFavorite,
                ingestions = it.ingestions.map { ingestion ->
                    IngestionSerializable(
                        substanceName = ingestion.substanceName,
                        time = ingestion.time,
                        endTime = ingestion.endTime,
                        creationDate = ingestion.creationDate,
                        administrationRoute = ingestion.administrationRoute,
                        dose = ingestion.dose,
                        estimatedDoseStandardDeviation = ingestion.estimatedDoseStandardDeviation,
                        isDoseAnEstimate = ingestion.isDoseAnEstimate,
                        units = ingestion.units,
                        notes = ingestion.notes,
                        stomachFullness = ingestion.stomachFullness,
                        consumerName = ingestion.consumerName,
                        customUnitId = ingestion.customUnitId
                    )
                },
                location = location?.let {
                    LocationSerializable(
                        name = it.name,
                        latitude = it.latitude,
                        longitude = it.longitude
                    )
                },
                ratings = it.ratings.map { rating ->
                    RatingSerializable(
                        option = rating.option,
                        time = rating.time,
                        creationDate = rating.creationDate
                    )
                },
                timedNotes = it.timedNotes.map { timedNote ->
                    TimedNoteSerializable(
                        creationDate = timedNote.creationDate,
                        time = timedNote.time,
                        note = timedNote.note,
                        color = timedNote.color,
                        isPartOfTimeline = timedNote.isPartOfTimeline
                    )
                }
            )
        }
        val customUnitsSerializable = experienceRepository.getAllCustomUnitsSorted().map {
            CustomUnitSerializable(
                id = it.id,
                substanceName = it.substanceName,
                name = it.name,
                creationDate = it.creationDate,
                administrationRoute = it.administrationRoute,
                dose = it.dose,
                estimatedDoseStandardDeviation = it.estimatedDoseStandardDeviation,
                isEstimate = it.isEstimate,
                isArchived = it.isArchived,
                unit = it.unit,
                unitPlural = it.unitPlural,
                originalUnit = it.originalUnit,
                note = it.note
            )
        }
        val ownerUserName = userPreferences.ownerUserNameFlow.firstOrNull() ?: "You"
        val avatarFile = AvatarUtil.getUserAvatar(context, ownerUserName)
        val avatars = if (avatarFile != null && avatarFile.exists()) {
            mapOf(ownerUserName to Base64.getEncoder().encodeToString(avatarFile.readBytes()))
        } else {
            emptyMap()
        }
        JournalExport(
            experiences = experiencesSerializable,
            substanceCompanions = experienceRepository.getAllSubstanceCompanions(),
            customSubstances = experienceRepository.getAllCustomSubstances(),
            customUnits = customUnitsSerializable,
            avatars = avatars
        )
    }

    fun encode(journalExport: JournalExport): String = Json.encodeToString(journalExport)

    suspend fun applyText(jsonText: String) = withContext(Dispatchers.IO) {
        val journalExport = Json { ignoreUnknownKeys = true }
            .decodeFromString<JournalExport>(jsonText)
        val decodedAvatars = journalExport.avatars.map { (userName, base64) ->
            userName to Base64.getDecoder().decode(base64)
        }
        experienceRepository.replaceEverything(journalExport)
        decodedAvatars.forEach { (userName, decoded) ->
            try {
                val avatarFile = AvatarUtil.getAvatarFile(context, userName)
                avatarFile.parentFile?.mkdirs()
                FileOutputStream(avatarFile).use { it.write(decoded) }
            } catch (_: Exception) {
            }
        }
    }
}