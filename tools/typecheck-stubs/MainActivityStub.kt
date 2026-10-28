// The real MainActivity is Compose-based and cannot be compiled here; the services only
// need its class reference for a PendingIntent, so a bare Activity stands in.
package app.amethyst.ui

class MainActivity : androidx.activity.ComponentActivity()
