package com.dhaval.echo.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.db.EchoCollection
import com.dhaval.echo.domain.collections.CollectionRepository
import com.dhaval.echo.domain.diary.DiaryRepository
import com.dhaval.echo.domain.tags.TagRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * How far through its ~22 questions this memory is.
 *
 * Understanding runs in the background long after capture, so the screen has to
 * be able to say "still working" without pretending a half-understood memory is
 * broken. Derived from the run rows, which means it survives the app being
 * killed and resumes counting where it left off.
 */
data class UnderstandingProgress(
    val settled: Int = 0,
    val total: Int = 0,
    val failed: Int = 0
) {
    /** True while questions remain unanswered — the only thing the UI shows. */
    val inProgress: Boolean get() = total > 0 && settled < total
}

/**
 * UI State for the Entry Details screen.
 */
data class EntryDetailsUiState(
    val entry: TimelineEntry? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val formattedDate: String = "",
    val tags: List<String> = emptyList(),
    val collections: List<EchoCollection> = emptyList(),
    val relatedEntries: List<TimelineEntry> = emptyList(),
    val segments: List<com.dhaval.echo.data.db.TranscriptionSegmentEntity> = emptyList(),
    val classification: com.dhaval.echo.data.db.MemoryClassificationEntity? = null,
    // Memory Understanding Engine (MU-0): what Echo concluded about this memory
    val linkedEntities: List<com.dhaval.echo.data.db.LinkedEntityView> = emptyList(),
    val extractedItems: List<com.dhaval.echo.data.db.ExtractedItem> = emptyList(),
    val understandingProgress: UnderstandingProgress = UnderstandingProgress()
) {
    /**
     * The interpretive verdicts — memory type, category, priority, intent.
     * Split out from [extractedItems] because they render as a facet row rather
     * than as action items, and because they carry no lifecycle.
     */
    val facets: List<com.dhaval.echo.data.db.ExtractedItem>
        get() = extractedItems.filter { it.kind in com.dhaval.echo.data.db.ItemKind.FACETS }
}

@HiltViewModel
class EntryDetailsViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val tagRepository: TagRepository,
    private val collectionRepository: CollectionRepository,
    private val intelligenceDao: com.dhaval.echo.data.db.IntelligenceDao,
    private val understandingDao: com.dhaval.echo.data.db.UnderstandingDao,
    private val worldDiscovery: com.dhaval.echo.data.understanding.WorldDiscoveryService,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val entryId: String = checkNotNull(savedStateHandle["entryId"])

    /** The Worlds this memory belongs to, discovered from the graph (Phase C). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val worlds: StateFlow<List<com.dhaval.echo.data.understanding.DiscoveredWorld>> =
        understandingDao.getLinkedEntities(entryId)
            .mapLatest { worldDiscovery.worldsForMemory(entryId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy • HH:mm", Locale.getDefault())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<EntryDetailsUiState> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf(EntryDetailsUiState())

        combine(
            diaryRepository.getEntryById(entryId),
            tagRepository.getTagsForEntry(entryId),
            collectionRepository.getCollectionsForEntry(entryId),
            intelligenceDao.getRelatedEntries(entryId, userId),
            // MUE graph output: entity links, evidence-board items, and how far
            // through its questions the memory is — all live, so the screen
            // fills in as each extractor lands rather than all at once.
            combine(
                understandingDao.getLinkedEntities(entryId),
                understandingDao.getItemsForMemory(entryId),
                understandingDao.getRunsForMemory(entryId)
            ) { links, items, runs -> Triple(links, items, runs) }
        ) { entry, tags, collections, related, understanding ->
        if (entry == null) {
            EntryDetailsUiState(isLoading = false, error = "Entry not found")
        } else {
            EntryDetailsUiState(
                entry = TimelineEntry(
                    id = entry.id,
                    title = entry.title,
                    audioPath = entry.audioPath,
                    durationMillis = entry.duration,
                    timestamp = entry.createdAt,
                    // The corrected sentence when we have one — that is what the
                    // user should read. The raw transcript stays in the database
                    // for playback alignment either way.
                    transcription = entry.readableText,
                    summary = entry.summary,
                    transcriptionStatus = entry.transcriptionStatus,
                    analysisStatus = entry.analysisStatus,
                    relatedMemoriesCount = related.size,
                    isSynced = false,
                    isFavorite = entry.favorite,
                    textContent = entry.textContent,
                    imagePaths = entry.imagePaths,
                    entryType = entry.entryType,
                    videos = entry.videos.orEmpty(),
                    visualSummary = entry.visualSummary,
                    photoCaptions = entry.photoCaptions.orEmpty()
                ),
                isLoading = false,
                formattedDate = entry.createdAt.format(dateFormatter),
                tags = tags,
                collections = collections,
                linkedEntities = understanding.first,
                extractedItems = understanding.second,
                understandingProgress = UnderstandingProgress(
                    settled = understanding.third.count {
                        it.status == com.dhaval.echo.data.db.ExtractionRunStatus.COMPLETED ||
                            it.status == com.dhaval.echo.data.db.ExtractionRunStatus.SKIPPED
                    },
                    total = com.dhaval.echo.domain.understanding.ExtractorRegistry.size,
                    failed = understanding.third.count {
                        it.status == com.dhaval.echo.data.db.ExtractionRunStatus.FAILED
                    }
                ),
                relatedEntries = related.map {
                    TimelineEntry(
                        id = it.id,
                        title = it.title,
                        audioPath = it.audioPath,
                        durationMillis = it.duration,
                        timestamp = it.createdAt,
                        transcription = it.transcript,
                        summary = it.summary,
                        transcriptionStatus = it.transcriptionStatus,
                        analysisStatus = it.analysisStatus,
                        relatedMemoriesCount = 0,
                        isSynced = false,
                        isFavorite = it.favorite,
                        entryType = it.entryType
                    )
                }
            )
        }
    }
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = EntryDetailsUiState()
)

    fun updateTitle(newTitle: String) {
        viewModelScope.launch {
            diaryRepository.updateTitle(entryId, newTitle)
        }
    }

    fun setPhotoCaption(photoPath: String, caption: String) {
        viewModelScope.launch {
            diaryRepository.setPhotoCaption(entryId, photoPath, caption)
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            diaryRepository.toggleFavorite(entryId)
        }
    }

    fun deleteEntry() {
        viewModelScope.launch {
            diaryRepository.deleteEntry(entryId)
        }
    }

    fun addTag(tag: String) {
        viewModelScope.launch {
            tagRepository.addTagToEntry(entryId, tag)
        }
    }

    fun removeTag(tag: String) {
        viewModelScope.launch {
            tagRepository.removeTagFromEntry(entryId, tag)
        }
    }

    fun addToCollection(collectionId: String) {
        viewModelScope.launch {
            collectionRepository.addEntryToCollection(entryId, collectionId)
        }
    }

    fun removeFromCollection(collectionId: String) {
        viewModelScope.launch {
            collectionRepository.removeEntryFromCollection(entryId, collectionId)
        }
    }
}
