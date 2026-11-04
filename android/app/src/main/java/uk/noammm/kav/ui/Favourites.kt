package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import uk.noammm.kav.data.Transit
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

class Favourite(val id: String, val name: String, val icon: String, val place: Transit.Place?) {
    fun copy(name: String = this.name, icon: String = this.icon, place: Transit.Place? = this.place) =
        Favourite(id, name, icon, place)

    companion object {
        const val HOME = "home"
        fun home() = Favourite(HOME, "Home", "home", null)
    }
}

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

val Favourite.label: String get() = if (id == Favourite.HOME) T("Home", "בית") else name

// The saved place under the favourite's own name, its address or stop name kept as the detail.
fun Favourite.named(p: Transit.Place): Transit.Place =
    if (label.isBlank() || label == p.name) p else Transit.Place(label, p.name, p.lat, p.lon, p.type, p.mode)

// The narrowest a column may get: a tile and the gap beside it.
private val SLOT = 72.dp + K.gap2

// Where a tile sits in the grid, counted in cells, so it glides to a new place whatever size the cells are.
private class GridSpot(val at: Animatable<Offset, AnimationVector2D>)

@Composable
private fun rememberSpot(index: Int, cols: Int, still: Boolean = false): GridSpot {
    val target = Offset((index % cols).toFloat(), (index / cols).toFloat())
    val spot = remember { GridSpot(Animatable(target, Offset.VectorConverter)) }
    LaunchedEffect(target, still) {
        if (!still) spot.at.animateTo(target, spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow))
    }
    return spot
}

// Equal columns across the whole width, rows filled from the start, every row as tall as the tallest tile.
@Composable
private fun SpotGrid(cols: Int, slots: Int, pitch: FloatArray, modifier: Modifier, content: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { K.gap1.roundToPx() }
    Layout(content, modifier) { measurables, constraints ->
        val cellW = constraints.maxWidth.toFloat() / cols
        val tiles = measurables.map { it.measure(Constraints.fixedWidth(cellW.toInt())) }
        val rowH = tiles.maxOfOrNull { it.height } ?: 0
        val rows = (slots + cols - 1) / cols
        pitch[0] = cellW
        pitch[1] = (rowH + gap).toFloat()
        layout(constraints.maxWidth, rows * rowH + (rows - 1).coerceAtLeast(0) * gap) {
            tiles.forEachIndexed { i, t ->
                val at = (measurables[i].layoutId as GridSpot).at.value
                t.placeRelative((at.x * cellW).roundToInt(), (at.y * pitch[1]).roundToInt())
            }
        }
    }
}

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
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    var order by remember(favourites) { mutableStateOf<List<Favourite>?>(null) }
    var held by remember { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val pitch = remember { floatArrayOf(1f, 1f) }
    val scope = rememberCoroutineScope()
    val shown = order ?: favourites
    val travel by animateFloatAsState(
        if (inEdit) 1f else 0f,
        spring(dampingRatio = .58f, stiffness = Spring.StiffnessMediumLow), label = "editTravel",
    )
    androidx.activity.compose.BackHandler(inEdit) { editMode = false; order = null; held = null; drag = Offset.Zero }

    // A favourite's tile, in its cell of the grid.
    @Composable
    fun Tile(f: Favourite, index: Int, cols: Int) {
        val set = f.place != null
        val lifted = held == f.id
        val spot = rememberSpot(index, cols, still = lifted)
        val origin = remember { floatArrayOf(0f, 0f) }
        val lift by animateFloatAsState(
            if (lifted) 1.08f else 1f,
            spring(dampingRatio = .6f, stiffness = Spring.StiffnessMedium), label = "lift",
        )
        val removable = onRemove != null && f.id != Favourite.HOME
        // Lets go of a tile where the finger left it, so it glides from there into its cell.
        fun drop(keep: Boolean) {
            val moved = order
            if (!keep) order = null
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                spot.at.snapTo(Offset(origin[0] + drag.x * dir / pitch[0], origin[1] + drag.y / pitch[1]))
                held = null
                drag = Offset.Zero
            }
            if (keep && moved != null) onReorder?.invoke(moved)
        }
        Column(
            Modifier.layoutId(spot)
                .zIndex(if (lifted) 1f else 0f)
                .graphicsLayer {
                    translationX = if (lifted) drag.x else 0f
                    translationY = if (lifted) drag.y else 0f
                    scaleX = lift
                    scaleY = lift
                }
                .clip(RoundedCornerShape(K.rTile))
                .then(
                    if (!inEdit) Modifier
                    else Modifier.pointerInput(favourites, dir, cols) {
                        detectDragGestures(
                            onDragStart = {
                                origin[0] = spot.at.value.x
                                origin[1] = spot.at.value.y
                                drag = Offset.Zero
                                held = f.id
                            },
                            onDragCancel = { drop(keep = false) },
                            onDragEnd = { drop(keep = true) },
                            onDrag = { change, by ->
                                change.consume()
                                drag += by
                                val list = (order ?: favourites).toMutableList()
                                val at = list.indexOfFirst { it.id == f.id }
                                if (at < 0) return@detectDragGestures
                                val col = (origin[0] + drag.x * dir / pitch[0]).roundToInt().coerceIn(0, cols - 1)
                                val row = (origin[1] + drag.y / pitch[1]).roundToInt().coerceAtLeast(0)
                                val to = (row * cols + col).coerceIn(0, list.size - 1)
                                if (to != at) {
                                    list.add(to, list.removeAt(at))
                                    order = list
                                }
                            },
                        )
                    },
                )
                .combinedClickable(
                    role = Role.Button,
                    onClick = { if (inEdit) onEdit(f) else onPick(f) },
                    onLongClick = if (inEdit) null else ({ onEdit(f) }),
                )
                .padding(horizontal = K.gap1, vertical = K.gap2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(K.rTile))
                        .background(if (set) K.fill else Color.Transparent)
                        .border(
                            if (lifted) 1.5.dp else 1.dp,
                            if (lifted) K.accent else if (set) Color.Transparent else K.separator,
                            RoundedCornerShape(K.rTile),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(favouriteIcon(f.icon), contentDescription = null, tint = if (set) K.text else K.textTertiary, modifier = Modifier.size(24.dp))
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = inEdit && removable,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp),
                    enter = scaleIn(
                        spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium), initialScale = .5f,
                    ) + fadeIn(tween(120)),
                    exit = scaleOut(tween(140), targetScale = .5f) + fadeOut(tween(120)),
                ) {
                    Box(
                        Modifier.size(24.dp).clip(K.capsule).background(K.critical)
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
            Spacer(Modifier.height(K.gap1))
            // The whole name: a long one takes a second line, and one still too long for two lines (or with a word
            // wider than the tile) shrinks a little before anything is cut.
            var labelSize by remember(f.label) { mutableStateOf(12.sp) }
            Text(
                f.label, style = K.type.caption, fontSize = labelSize, color = if (set) K.text else K.textSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                onTextLayout = { if ((it.hasVisualOverflow || it.didOverflowWidth) && labelSize > 10.sp) labelSize *= 0.92f },
            )
        }
    }

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = K.gap2)) {
            val cols = ((maxWidth + K.gap2) / SLOT).toInt().coerceAtLeast(1)
            // Editing adds the Add tile before Done, so the grid gains a cell.
            val slots = shown.size + if (inEdit) 2 else 1
            SpotGrid(
                cols, slots, pitch,
                Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 1f, stiffness = 500f)),
            ) {
                shown.forEachIndexed { i, f -> key("favourite", f.id) { Tile(f, i, cols) } }
                if (!manage) key("add") {
                    StripTile(
                        Icons.Rounded.Add, T("Add", "הוספה"), T("Add a favourite", "הוספת מועדף"),
                        on = false, onClick = onAdd, modifier = Modifier.layoutId(rememberSpot(shown.size, cols)),
                    )
                } else {
                    if (inEdit || travel > 0f) key("add") {
                        StripTile(
                            Icons.Rounded.Add, T("Add", "הוספה"), T("Add a favourite", "הוספת מועדף"),
                            on = false, onClick = onAdd, enabled = inEdit,
                            modifier = Modifier.layoutId(rememberSpot(shown.size, cols)).graphicsLayer {
                                val grown = .3f + .7f * travel.coerceAtLeast(0f)
                                alpha = travel.coerceIn(0f, 1f)
                                scaleX = grown
                                scaleY = grown
                            },
                        )
                    }
                    key("edit") {
                        val flipped = travel > .5f
                        StripTile(
                            if (flipped) Icons.Rounded.Check else Icons.Rounded.Edit,
                            if (flipped) T("Done", "סיום") else T("Edit", "עריכה"),
                            if (inEdit) T("Finish editing favourites", "סיום עריכת המועדפים")
                            else T("Sort and remove favourites", "סידור והסרה של מועדפים"),
                            on = inEdit,
                            onClick = { editMode = !editMode; order = null; held = null; drag = Offset.Zero },
                            modifier = Modifier.layoutId(rememberSpot(shown.size + if (inEdit) 1 else 0, cols)),
                            iconModifier = Modifier.graphicsLayer {
                                rotationY = travel * 180f - if (flipped) 180f else 0f
                                cameraDistance = 12f * density
                            },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = inEdit,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
        ) {
            Text(
                T("Drag to reorder, tap to edit", "גררו לסידור, הקישו לעריכה"),
                style = K.type.caption, color = K.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )
        }
    }
}

@Composable
private fun PlaceLine(f: Favourite, onChange: () -> Unit) {
    val p = f.place
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).panel(K.rTile)
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
        Text(T("Change", "שינוי"), style = K.type.subhead, color = K.accentInk)
    }
}

@Composable
private fun StripTile(
    icon: ImageVector,
    label: String,
    description: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fill by animateColorAsState(if (on) K.accent else K.fill, tween(220), label = "tileFill")
    val ink by animateColorAsState(if (on) K.onAccent else K.textSecondary, tween(220), label = "tileInk")
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap1, vertical = K.gap2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(K.rTile)).background(fill),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = description, tint = ink, modifier = iconModifier.size(24.dp)) }
        Spacer(Modifier.height(K.gap1))
        Text(label, style = K.type.caption, color = K.textSecondary)
    }
}

@Composable
fun FavouriteEditor(
    existing: Favourite?,
    onSave: (name: String, icon: String) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
    onChangePlace: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var icon by remember { mutableStateOf(existing?.icon ?: "star") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap4),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Text(
                if (existing == null) T("New favourite", "מועדף חדש") else existing.label,
                style = K.type.title,
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
                        Modifier.size(52.dp).clip(RoundedCornerShape(K.rTile))
                            .background(if (on) K.accent.copy(alpha = .18f) else K.plate)
                            .border(1.dp, if (on) K.accent else K.border, RoundedCornerShape(K.rTile))
                            .clickable(role = Role.RadioButton) { icon = key },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(vector, contentDescription = key, tint = if (on) K.accentInk else K.muted, modifier = Modifier.size(26.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                if (onRemove != null) {
                    KavButton(T("Remove", "הסרה"), onRemove, Modifier.heightIn(min = 52.dp), style = ButtonStyle.Destructive)
                }
                Spacer(Modifier.weight(1f))
                KavButton(T("Cancel", "ביטול"), onDismiss, Modifier.heightIn(min = 52.dp), style = ButtonStyle.Secondary)
                val ready = name.isNotBlank() || existing?.id == Favourite.HOME
                KavButton(
                    if (existing == null) T("Next", "הבא") else T("Save", "שמירה"),
                    { onSave(name.trim().ifBlank { existing?.name ?: "" }, icon) }, enabled = ready,
                )
            }
        }
    }
}
