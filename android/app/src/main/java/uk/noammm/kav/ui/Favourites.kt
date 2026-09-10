package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * The strip above a search: Home first, then the rider's own places, then a plus.
 * A place not set yet opens the search to set it, the first tap on Home asks where
 * home is, every later tap goes there.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavouriteStrip(
    favourites: List<Favourite>,
    onPick: (Favourite) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Favourite) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = K.gap3,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = horizontalPadding, vertical = K.gap2),
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        favourites.forEach { f ->
            val set = f.place != null
            Column(
                Modifier.width(72.dp).clip(RoundedCornerShape(K.rControl))
                    .combinedClickable(role = Role.Button, onClick = { onPick(f) }, onLongClick = { onEdit(f) })
                    .padding(vertical = K.gap2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (set) K.accent.copy(alpha = .16f) else K.plate)
                        .border(1.dp, if (set) K.accent.copy(alpha = .5f) else K.border, RoundedCornerShape(999.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(favouriteIcon(f.icon), contentDescription = null, tint = if (set) K.accent else K.muted, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(5.dp))
                Text(f.name, fontSize = 12.sp, color = if (set) K.text else K.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier.width(72.dp).clip(RoundedCornerShape(K.rControl)).clickable(role = Role.Button, onClick = onAdd)
                .padding(vertical = K.gap2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(999.dp)).background(K.plate)
                    .border(1.dp, K.border, RoundedCornerShape(999.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, contentDescription = "Add a favourite", tint = K.muted, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.height(5.dp))
            Text("Add", fontSize = 12.sp, color = K.muted)
        }
    }
}

/** Name it and pick its icon; where it is comes next, from the search. */
@Composable
fun FavouriteEditor(
    existing: Favourite?,
    onSave: (name: String, icon: String) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var icon by remember { mutableStateOf(existing?.icon ?: "star") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap4),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Text(
                if (existing == null) "New favourite" else existing.name,
                fontSize = 18.sp, color = K.text, fontWeight = FontWeight.SemiBold,
            )
            if (existing?.id != Favourite.HOME) KavField(name, { name = it }, "Name", autoFocus = existing == null)
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
                ) { Text("Remove", fontSize = 14.sp, color = K.critical) }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill)).background(K.plateStrong)
                        .clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = K.gap4),
                    contentAlignment = Alignment.Center,
                ) { Text("Cancel", fontSize = 14.sp, color = K.text) }
                val ready = name.isNotBlank() || existing?.id == Favourite.HOME
                Box(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill))
                        .background(if (ready) K.accent else K.surface4)
                        .clickable(enabled = ready, role = Role.Button) { onSave(name.trim().ifBlank { existing?.name ?: "" }, icon) }
                        .padding(horizontal = K.gap4),
                    contentAlignment = Alignment.Center,
                ) { Text(if (existing == null) "Next" else "Save", fontSize = 14.sp, color = if (ready) K.bg else K.muted, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
