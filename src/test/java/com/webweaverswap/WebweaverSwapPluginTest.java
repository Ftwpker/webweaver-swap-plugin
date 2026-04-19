package com.webweaverswap;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class WebweaverSwapPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(WebweaverSwapPlugin.class);
		RuneLite.main(args);
	}
}
