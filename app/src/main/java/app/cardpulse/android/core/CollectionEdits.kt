package app.cardpulse.android.core

/** "NM · Normal · EN": which exact copy a collection row is. */
fun CollectionItemDto.rowLabel(): String = "$condition · $variant · ${CardLanguages.label(lang)}"

/** The collection without one row, as after removing it. */
fun List<CollectionItemDto>.without(itemId: Int): List<CollectionItemDto> = filterNot { it.id == itemId }

/** The collection with one row replaced where it stands, so a changed quantity doesn't move the card in the list. */
fun List<CollectionItemDto>.replacing(item: CollectionItemDto): List<CollectionItemDto> =
    map { if (it.id == item.id) item else it }

/**
 * True when removing this row also deletes the owner's photo of the card: the server keeps a photo only while some
 * row of that card is left.
 */
fun CollectionItemDto.takesItsPhotoWhenRemoved(all: List<CollectionItemDto>): Boolean =
    hasScanPhoto && all.none { it.id != id && it.cardId == cardId }

/** A card made by hand (on the website or in this app) rather than taken from the catalogue. It has no market price. */
fun CollectionItemDto.isCustomCard(): Boolean = card?.isCustom == true || cardId?.startsWith("custom-") == true
