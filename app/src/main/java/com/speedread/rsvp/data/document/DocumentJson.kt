package com.speedread.rsvp.data.document

import com.speedread.rsvp.Constants
import com.speedread.rsvp.util.Logger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.speedread.rsvp.data.parser.FigureRegion as ImporterFigureRegion
import com.speedread.rsvp.data.parser.PageBoundary as ImporterPageBoundary

object DocumentJson {

    private val json: Json = Json {
        prettyPrint = Constants.JsonConfig.PRETTY_PRINT
        ignoreUnknownKeys = Constants.JsonConfig.IGNORE_UNKNOWN_KEYS
        encodeDefaults = Constants.JsonConfig.ENCODE_DEFAULTS
    }

    private val pageBoundaryListSerializer = ListSerializer(PageBoundary.serializer())
    private val figureRegionListSerializer = ListSerializer(FigureRegion.serializer())

    fun List<PageBoundary>.encodeToJson(): String =
        json.encodeToString(pageBoundaryListSerializer, this)

    @JvmName("encodeFigureRegionsToJson")
    fun List<FigureRegion>.encodeToJson(): String =
        json.encodeToString(figureRegionListSerializer, this)

    fun decodePageBoundaries(raw: String?): List<PageBoundary>? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(pageBoundaryListSerializer, raw)
        } catch (e: SerializationException) {
            Logger.w("DocumentJson", "Corrupt pageBoundariesJson (SerializationException); dropping boundaries", e)
            null
        } catch (e: IllegalArgumentException) {
            Logger.w("DocumentJson", "Corrupt pageBoundariesJson (IllegalArgumentException); dropping boundaries", e)
            null
        }
    }

    fun decodeFigureRegions(raw: String?): List<FigureRegion>? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(figureRegionListSerializer, raw)
        } catch (e: SerializationException) {
            Logger.w("DocumentJson", "Corrupt figureRegionsJson (SerializationException); dropping figures", e)
            null
        } catch (e: IllegalArgumentException) {
            Logger.w("DocumentJson", "Corrupt figureRegionsJson (IllegalArgumentException); dropping figures", e)
            null
        }
    }
}

fun PageBoundary.toImporter(): ImporterPageBoundary =
    ImporterPageBoundary(
        pageNumber = pageNumber,
        startWordIndex = startWordIndex,
        endWordIndex = endWordIndex,
        wordCount = wordCount
    )

fun ImporterPageBoundary.toDocument(): PageBoundary =
    PageBoundary(
        pageNumber = pageNumber,
        startWordIndex = startWordIndex,
        endWordIndex = endWordIndex,
        wordCount = wordCount
    )

fun FigureRegion.toImporter(): ImporterFigureRegion =
    ImporterFigureRegion(
        pageNumber = pageNumber,
        left = left,
        top = top,
        width = width,
        height = height,
        aspectRatio = aspectRatio,
        anchorWordIndex = anchorWordIndex,
        labelStartWordIndex = labelStartWordIndex,
        labelEndWordIndex = labelEndWordIndex
    )

fun ImporterFigureRegion.toDocument(): FigureRegion =
    FigureRegion(
        pageNumber = pageNumber,
        left = left,
        top = top,
        width = width,
        height = height,
        aspectRatio = aspectRatio,
        anchorWordIndex = anchorWordIndex,
        labelStartWordIndex = labelStartWordIndex,
        labelEndWordIndex = labelEndWordIndex
    )
