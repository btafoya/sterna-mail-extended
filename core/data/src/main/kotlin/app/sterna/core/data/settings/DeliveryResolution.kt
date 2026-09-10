package app.sterna.core.data.settings

/**
 * The mode one account really runs under: its own choice, or [global] while it has never made one.
 *
 * A [stored] null is "never chose", not "instant": every account record written before accounts
 * could choose one carries no value, and must keep behaving as the single setting it was stored
 * under. Answering [DeliveryMode.INSTANT] there would move every battery-saver install onto a live
 * connection on the update, with nothing on screen saying so.
 */
fun resolveDeliveryMode(stored: DeliveryMode?, global: DeliveryMode): DeliveryMode = stored ?: global
