package dev.createuniversal.client.nav;

import net.minecraft.client.Minecraft;

/** Solo cliente: abrir el mapa (lo llaman la mesa de mapa y el proyector). */
public final class NavOpen {
    private NavOpen() {}

    public static void open() {
        Minecraft.getInstance().setScreen(new NavigationScreen());
    }
}
