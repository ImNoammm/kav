package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import uk.noammm.kav.data.Moovit

/** A place with a name of the rider's own. Home is built in; the rest are added. */
class Favourite(val id: String, val name: String, val icon: String, val place: Moovit.Place?) {
    fun copy(name: String = this.name, icon: String = this.icon, place: Moovit.Place? = this.place) =
        Favourite(id, name, icon, place)

    companion object {
        const val HOME = "home"
        fun home() = Favourite(HOME, "Home", "home", null)
    }
}

/** Every icon a favourite can wear, by the key it is stored under. */
val FavouriteIcons: List<Pair<String, ImageVector>> = listOf(
    "home" to Icons.Rounded.Home, "work" to Icons.Rounded.Work, "school" to Icons.Rounded.School,
    "apartment" to Icons.Rounded.Apartment, "business" to Icons.Rounded.Business, "cottage" to Icons.Rounded.Cottage,
    "villa" to Icons.Rounded.Villa, "cabin" to Icons.Rounded.Cabin, "castle" to Icons.Rounded.Castle,
    "favorite" to Icons.Rounded.Favorite, "star" to Icons.Rounded.Star, "bolt" to Icons.Rounded.Bolt,
    "person" to Icons.Rounded.Person, "people" to Icons.Rounded.People, "groups" to Icons.Rounded.Groups,
    "child" to Icons.Rounded.ChildCare, "elderly" to Icons.Rounded.Elderly, "pets" to Icons.Rounded.Pets,
    "restaurant" to Icons.Rounded.Restaurant, "cafe" to Icons.Rounded.LocalCafe, "coffee" to Icons.Rounded.Coffee,
    "bar" to Icons.Rounded.LocalBar, "wine" to Icons.Rounded.WineBar, "pizza" to Icons.Rounded.LocalPizza,
    "fastfood" to Icons.Rounded.Fastfood, "icecream" to Icons.Rounded.Icecream, "cake" to Icons.Rounded.Cake,
    "bakery" to Icons.Rounded.BakeryDining, "lunch" to Icons.Rounded.LunchDining, "dinner" to Icons.Rounded.DinnerDining,
    "ramen" to Icons.Rounded.RamenDining, "grocery" to Icons.Rounded.LocalGroceryStore, "cart" to Icons.Rounded.ShoppingCart,
    "bag" to Icons.Rounded.ShoppingBag, "mall" to Icons.Rounded.LocalMall, "store" to Icons.Rounded.Storefront,
    "hospital" to Icons.Rounded.LocalHospital, "pharmacy" to Icons.Rounded.LocalPharmacy, "medical" to Icons.Rounded.MedicalServices,
    "gym" to Icons.Rounded.FitnessCenter, "pool" to Icons.Rounded.Pool, "run" to Icons.Rounded.DirectionsRun,
    "bike" to Icons.Rounded.DirectionsBike, "soccer" to Icons.Rounded.SportsSoccer, "basketball" to Icons.Rounded.SportsBasketball,
    "tennis" to Icons.Rounded.SportsTennis, "stadium" to Icons.Rounded.Stadium, "esports" to Icons.Rounded.SportsEsports,
    "park" to Icons.Rounded.Park, "forest" to Icons.Rounded.Forest, "terrain" to Icons.Rounded.Terrain,
    "beach" to Icons.Rounded.BeachAccess, "hiking" to Icons.Rounded.Hiking, "kayak" to Icons.Rounded.Kayaking,
    "surf" to Icons.Rounded.Surfing, "sail" to Icons.Rounded.Sailing, "anchor" to Icons.Rounded.Anchor,
    "water" to Icons.Rounded.Water, "waves" to Icons.Rounded.Waves, "sun" to Icons.Rounded.WbSunny,
    "night" to Icons.Rounded.NightsStay, "umbrella" to Icons.Rounded.Umbrella, "fire" to Icons.Rounded.LocalFireDepartment,
    "museum" to Icons.Rounded.Museum, "theater" to Icons.Rounded.TheaterComedy, "movie" to Icons.Rounded.Movie,
    "music" to Icons.Rounded.MusicNote, "piano" to Icons.Rounded.Piano, "headphones" to Icons.Rounded.Headphones,
    "nightlife" to Icons.Rounded.Nightlife, "celebration" to Icons.Rounded.Celebration, "festival" to Icons.Rounded.Festival,
    "library" to Icons.Rounded.LocalLibrary, "book" to Icons.Rounded.MenuBook, "science" to Icons.Rounded.Science,
    "computer" to Icons.Rounded.Computer, "engineering" to Icons.Rounded.Engineering, "build" to Icons.Rounded.Build,
    "handyman" to Icons.Rounded.Handyman, "factory" to Icons.Rounded.Factory, "warehouse" to Icons.Rounded.Warehouse,
    "agriculture" to Icons.Rounded.Agriculture, "garage" to Icons.Rounded.Garage, "gas" to Icons.Rounded.LocalGasStation,
    "ev" to Icons.Rounded.EvStation, "parking" to Icons.Rounded.LocalParking, "carwash" to Icons.Rounded.LocalCarWash,
    "car" to Icons.Rounded.DirectionsCar, "bus" to Icons.Rounded.DirectionsBus, "train" to Icons.Rounded.Train,
    "tram" to Icons.Rounded.Tram, "subway" to Icons.Rounded.Subway, "flight" to Icons.Rounded.Flight,
    "airport" to Icons.Rounded.LocalAirport, "taxi" to Icons.Rounded.LocalTaxi, "moped" to Icons.Rounded.Moped,
    "scooter" to Icons.Rounded.ElectricScooter, "motorbike" to Icons.Rounded.TwoWheeler, "hotel" to Icons.Rounded.Hotel,
    "bed" to Icons.Rounded.KingBed, "sofa" to Icons.Rounded.Weekend, "chair" to Icons.Rounded.Chair,
    "kitchen" to Icons.Rounded.Kitchen, "laundry" to Icons.Rounded.LocalLaundryService, "shower" to Icons.Rounded.Shower,
    "bath" to Icons.Rounded.Bathtub, "hottub" to Icons.Rounded.HotTub, "fireplace" to Icons.Rounded.Fireplace,
    "balcony" to Icons.Rounded.Balcony, "yard" to Icons.Rounded.Yard, "deck" to Icons.Rounded.Deck,
    "florist" to Icons.Rounded.LocalFlorist, "spa" to Icons.Rounded.Spa, "meditate" to Icons.Rounded.SelfImprovement,
    "haircut" to Icons.Rounded.ContentCut, "face" to Icons.Rounded.Face, "wardrobe" to Icons.Rounded.Checkroom,
    "diamond" to Icons.Rounded.Diamond, "watch" to Icons.Rounded.Watch, "camera" to Icons.Rounded.CameraAlt,
    "palette" to Icons.Rounded.Palette, "brush" to Icons.Rounded.Brush, "toys" to Icons.Rounded.Toys,
    "trophy" to Icons.Rounded.EmojiEvents, "wave" to Icons.Rounded.EmojiPeople, "volunteer" to Icons.Rounded.VolunteerActivism,
    "church" to Icons.Rounded.Church, "synagogue" to Icons.Rounded.Synagogue, "mosque" to Icons.Rounded.Mosque,
    "temple" to Icons.Rounded.TempleBuddhist, "hindu" to Icons.Rounded.TempleHindu, "bank" to Icons.Rounded.AccountBalance,
    "court" to Icons.Rounded.Gavel, "police" to Icons.Rounded.LocalPolice, "security" to Icons.Rounded.Security,
    "phone" to Icons.Rounded.Phone, "mail" to Icons.Rounded.Mail, "post" to Icons.Rounded.LocalPostOffice,
    "atm" to Icons.Rounded.LocalAtm, "money" to Icons.Rounded.AttachMoney, "card" to Icons.Rounded.CreditCard,
    "savings" to Icons.Rounded.Savings, "wallet" to Icons.Rounded.Wallet, "paid" to Icons.Rounded.Paid,
    "elevator" to Icons.Rounded.Elevator, "escalator" to Icons.Rounded.Escalator, "stairs" to Icons.Rounded.Stairs,
    "wc" to Icons.Rounded.Wc, "crib" to Icons.Rounded.Crib, "dining" to Icons.Rounded.Dining,
    "living" to Icons.Rounded.Living, "blender" to Icons.Rounded.Blender, "microwave" to Icons.Rounded.Microwave,
    "egg" to Icons.Rounded.Egg, "drink" to Icons.Rounded.LocalDrink, "play" to Icons.Rounded.LocalPlay,
    "attractions" to Icons.Rounded.Attractions, "casino" to Icons.Rounded.Casino, "skate" to Icons.Rounded.Skateboarding,
    "ski" to Icons.Rounded.DownhillSkiing, "snowboard" to Icons.Rounded.Snowboarding, "row" to Icons.Rounded.Rowing,
    "motorsport" to Icons.Rounded.SportsMotorsports, "flag" to Icons.Rounded.Flag, "pin" to Icons.Rounded.Place,
    "map" to Icons.Rounded.Map, "explore" to Icons.Rounded.Explore, "public" to Icons.Rounded.Public,
    "key" to Icons.Rounded.Key, "lock" to Icons.Rounded.Lock, "clock" to Icons.Rounded.Schedule,
    "event" to Icons.Rounded.Event, "gift" to Icons.Rounded.CardGiftcard, "balloon" to Icons.Rounded.Cake,
)

fun favouriteIcon(key: String): ImageVector =
    FavouriteIcons.firstOrNull { it.first == key }?.second ?: Icons.Rounded.Place

/**
 * What to call a favourite on screen. Home is the one name Kav chose rather than the
 * rider, and it is the one that cannot be renamed, so it follows the language instead
 * of being stored in it - a strip set up in Hebrew would otherwise still read "Home"
 * after the language was switched back, and the other way round.
 */
val Favourite.label: String get() = if (id == Favourite.HOME) T("Home", "בית") else name

/** One slot in the strip: the tile plus the gap that follows it. */
private val SLOT = 72.dp + K.gap2

/**
 * The strip above a search: Home first, then the rider's own places, then a plus.
 * A place not set yet opens the search to set it, the first tap on Home asks where
 * home is, every later tap goes there.
 *
 * With [onReorder] a pen sits over the strip, and tapping it turns the strip into an
 * editor: every place that may go wears an X, a tap renames instead of travelling, and
 * any tile can be dragged straight into a new order. Sorting behind a mode rather than
 * behind a long press is what was asked for. A hold that silently became a drag was a
 * gesture you had to be told about, it took the hold away from the editor it used to
 * open, and there was nowhere to put a Remove that did not mean opening that editor
 * first. The pen went above the places rather than after them because a tile at the end
 * has to be scrolled to once the strip outgrows the screen, which is exactly the strip
 * that needs sorting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavouriteStrip(
    favourites: List<Favourite>,
    onPick: (Favourite) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Favourite) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = K.gap3,
    onReorder: ((List<Favourite>) -> Unit)? = null,
    onRemove: ((Favourite) -> Unit)? = null,
) {
    val manage = onReorder != null
    var editMode by remember { mutableStateOf(false) }
    val inEdit = manage && editMode
    val slotPx = with(LocalDensity.current) { SLOT.toPx() }
    // A drag reports raw screen pixels whichever way the interface runs, but in Hebrew
    // the tiles are laid out right to left, so travelling right is travelling towards
    // the front of the list.
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    // The live order while a tile is in the air. Null the rest of the time, so the
    // strip goes back to reading straight from the caller's list.
    var order by remember(favourites) { mutableStateOf<List<Favourite>?>(null) }
    var held by remember { mutableStateOf<String?>(null) }
    var dx by remember { mutableFloatStateOf(0f) }
    val shown = order ?: favourites

    Column(Modifier.fillMaxWidth()) {
        if (manage) Row(
            Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A mode with no visible rules is a mode you have to be told about. Once
            // the pen is on, both gestures change meaning at once, so both are named
            // here rather than left to be discovered a tile at a time.
            AnimatedVisibility(
                visible = inEdit,
                modifier = Modifier.weight(1f, fill = false),
                // out of the pen's own edge and back into it, so the sentence and the
                // button that turns it on read as one thing opening
                enter = fadeIn(tween(180)) + expandHorizontally(tween(220), Alignment.End),
                exit = fadeOut(tween(120)) + shrinkHorizontally(tween(180), Alignment.End),
            ) {
                Text(
                    T("Drag to reorder, tap to edit", "גררו לסידור, הקישו לעריכה"),
                    fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = K.gap2),
                )
            }
            // the pill and its glyph cross over rather than cut, so the mode looks
            // turned on rather than swapped out from under the finger
            val penPlate by animateColorAsState(
                if (inEdit) K.accent.copy(alpha = .16f) else K.plate, tween(220), label = "penPlate",
            )
            val penTint by animateColorAsState(if (inEdit) K.accent else K.muted, tween(220), label = "penTint")
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(999.dp))
                    .background(penPlate)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (inEdit) T("Finish editing favourites", "סיום עריכת המועדפים")
                        else T("Sort and remove favourites", "סידור והסרה של מועדפים"),
                    ) {
                        editMode = !editMode
                        // whatever was half-dragged when the mode closed is not an order
                        order = null; held = null; dx = 0f
                    },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    inEdit,
                    transitionSpec = {
                        (fadeIn(tween(160)) + scaleIn(tween(220), initialScale = .6f)) togetherWith
                            (fadeOut(tween(120)) + scaleOut(tween(180), targetScale = .6f))
                    },
                    label = "penGlyph",
                ) { editing ->
                    Icon(
                        if (editing) Icons.Rounded.Check else Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = penTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                // While a tile is in the air the strip itself has to hold still: the row
                // and the tile want the same horizontal drag, and a strip sliding under the
                // finger would carry the tile past neighbours it never passed.
                .horizontalScroll(rememberScrollState(), enabled = held == null)
                .padding(horizontal = horizontalPadding, vertical = K.gap2),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            shown.forEach { f ->
                val set = f.place != null
                val lifted = held == f.id
                val lift by animateFloatAsState(
                    if (lifted) 1.08f else 1f,
                    spring(dampingRatio = .6f, stiffness = Spring.StiffnessMedium), label = "lift",
                )
                // Home is the one place always here: it can be moved and re-iconed, never
                // deleted, so it is the one tile that wears no X.
                val removable = onRemove != null && f.id != Favourite.HOME
                Column(
                    Modifier.width(72.dp)
                        .zIndex(if (lifted) 1f else 0f)
                        // A layer, not offset+scale: translationX is screen pixels and is
                        // never mirrored, so the tile tracks the finger in Hebrew too.
                        .graphicsLayer {
                            // the finger's own travel goes in raw: a tile that eases
                            // after the drag stops being the thing being moved. Only
                            // the lift itself is sprung.
                            translationX = if (lifted) dx else 0f
                            scaleX = lift
                            scaleY = lift
                        }
                        .clip(RoundedCornerShape(K.rControl))
                        .then(
                            if (!inEdit) Modifier
                            else Modifier.pointerInput(favourites, dir) {
                                detectDragGestures(
                                    onDragStart = { held = f.id; dx = 0f },
                                    onDragCancel = { held = null; dx = 0f; order = null },
                                    onDragEnd = {
                                        val moved = order
                                        held = null; dx = 0f
                                        if (moved != null) onReorder?.invoke(moved)
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        dx += drag.x
                                        val list = (order ?: favourites).toMutableList()
                                        val at = list.indexOfFirst { it.id == f.id }
                                        if (at < 0) return@detectDragGestures
                                        // one neighbour per half-slot travelled, and the
                                        // offset is repaid each time so the tile stays
                                        // under the finger instead of running ahead of it
                                        val travelled = dx * dir
                                        if (travelled > slotPx / 2 && at < list.size - 1) {
                                            list.add(at + 1, list.removeAt(at))
                                            dx -= slotPx * dir
                                            order = list
                                        } else if (travelled < -slotPx / 2 && at > 0) {
                                            list.add(at - 1, list.removeAt(at))
                                            dx += slotPx * dir
                                            order = list
                                        }
                                    },
                                )
                            },
                        )
                        .combinedClickable(
                            role = Role.Button,
                            // in the editor a tap is for renaming it, not for going there
                            onClick = { if (inEdit) onEdit(f) else onPick(f) },
                            // with no mode running, the hold is the editor it always was
                            onLongClick = if (inEdit) null else ({ onEdit(f) }),
                        )
                        .padding(vertical = K.gap2),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Unclipped on purpose, so the X can sit on the circle's corner and
                    // overhang it into the tile's own padding.
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(46.dp).clip(RoundedCornerShape(999.dp))
                                .background(if (set) K.accent.copy(alpha = .16f) else K.plate)
                                .border(
                                    1.dp,
                                    if (lifted) K.accent else if (set) K.accent.copy(alpha = .5f) else K.border,
                                    RoundedCornerShape(999.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(favouriteIcon(f.icon), contentDescription = null, tint = if (set) K.accent else K.muted, modifier = Modifier.size(24.dp))
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = inEdit && removable,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp),
                            // onto the corner and back into it, so a strip full of
                            // tiles arms and disarms as one movement
                            enter = scaleIn(
                                spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium), initialScale = .5f,
                            ) + fadeIn(tween(120)),
                            exit = scaleOut(tween(140), targetScale = .5f) + fadeOut(tween(120)),
                        ) {
                            Box(
                                Modifier.size(24.dp).clip(RoundedCornerShape(999.dp)).background(K.critical)
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = T("Remove ${f.label}", "הסרת ${f.label}"),
                                    ) { onRemove?.invoke(f) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = null, tint = K.bg, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(f.label, fontSize = 12.sp, color = if (set) K.text else K.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            StripTile(
                Icons.Rounded.Add,
                T("Add", "הוספה"),
                T("Add a favourite", "הוספת מועדף"),
                on = false,
                onClick = onAdd,
            )
        }
    }
}

/** Where a favourite is, under its name, as the way to send it somewhere else. */
@Composable
private fun PlaceLine(f: Favourite, onChange: () -> Unit) {
    val p = f.place
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(K.rControl))
            .background(K.plate)
            .clickable(
                role = Role.Button,
                onClickLabel = T("Change where ${f.label} is", "שינוי המיקום של ${f.label}"),
                onClick = onChange,
            )
            .padding(horizontal = K.gap3, vertical = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        Icon(Icons.Rounded.Place, contentDescription = null, tint = K.muted, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p?.name?.takeIf { it.isNotBlank() } ?: T("Not set yet", "עדיין לא נקבע"),
                fontSize = 14.sp, color = if (p == null) K.muted else K.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            p?.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(T("Change", "שינוי"), fontSize = 13.sp, color = K.accent)
    }
}

/** The tiles at the end of the strip: a favourite's shape with no place behind it. */
@Composable
private fun StripTile(
    icon: ImageVector,
    label: String,
    description: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(K.rControl))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = K.gap2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(999.dp))
                .background(if (on) K.accent.copy(alpha = .16f) else K.plate)
                .border(1.dp, if (on) K.accent else K.border, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = description, tint = if (on) K.accent else K.muted, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 12.sp, color = if (on) K.accent else K.muted)
    }
}

/**
 * Name it and pick its icon; where it is comes next, from the search.
 *
 * For a favourite that already exists, where it is shows under the name and is a
 * button back into that search. Without it a place could be set exactly once, on the
 * first tap that asked where it was, and a rider who had moved house, or who had
 * placed Home on the wrong side of the street, had nothing to press: the editor knew
 * the place and would not show it, and every other route into it led to travelling
 * there instead of changing it.
 */
@Composable
fun FavouriteEditor(
    existing: Favourite?,
    onSave: (name: String, icon: String) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** Hand the rider back to the search to re-place this one. Null hides the row. */
    onChangePlace: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var icon by remember { mutableStateOf(existing?.icon ?: "star") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap4),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Text(
                if (existing == null) T("New favourite", "מועדף חדש") else existing.label,
                fontSize = 18.sp, color = K.text, fontWeight = FontWeight.SemiBold,
            )
            if (existing?.id != Favourite.HOME) KavField(name, { name = it }, T("Name", "שם"), autoFocus = existing == null)
            if (existing != null && onChangePlace != null) PlaceLine(existing, onChangePlace)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(52.dp), modifier = Modifier.heightIn(max = 280.dp),
                horizontalArrangement = Arrangement.spacedBy(K.gap1), verticalArrangement = Arrangement.spacedBy(K.gap1),
            ) {
                items(FavouriteIcons, key = { it.first }) { (key, vector) ->
                    val on = key == icon
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) K.accent.copy(alpha = .18f) else K.plate)
                            .border(1.dp, if (on) K.accent else K.border, RoundedCornerShape(14.dp))
                            .clickable(role = Role.RadioButton) { icon = key },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(vector, contentDescription = key, tint = if (on) K.accent else K.muted, modifier = Modifier.size(26.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                if (onRemove != null) Box(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill)).background(K.plateStrong)
                        .clickable(role = Role.Button, onClick = onRemove).padding(horizontal = K.gap4),
                    contentAlignment = Alignment.Center,
                ) { Text(T("Remove", "הסרה"), fontSize = 14.sp, color = K.critical) }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill)).background(K.plateStrong)
                        .clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = K.gap4),
                    contentAlignment = Alignment.Center,
                ) { Text(T("Cancel", "ביטול"), fontSize = 14.sp, color = K.text) }
                val ready = name.isNotBlank() || existing?.id == Favourite.HOME
                Box(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill))
                        .background(if (ready) K.accent else K.surface4)
                        .clickable(enabled = ready, role = Role.Button) { onSave(name.trim().ifBlank { existing?.name ?: "" }, icon) }
                        .padding(horizontal = K.gap4),
                    contentAlignment = Alignment.Center,
                ) { Text(if (existing == null) T("Next", "הבא") else T("Save", "שמירה"), fontSize = 14.sp, color = if (ready) K.bg else K.muted, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
