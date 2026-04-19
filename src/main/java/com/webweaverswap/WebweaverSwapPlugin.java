/*
 * Copyright (c) 2026
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.webweaverswap;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.kit.KitType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Webweaver Swap",
	description = "Swap Webweaver bow visuals to Craw's bow",
	tags = {"bow", "craw", "ranged", "webweaver"},
	enabledByDefault = false
)
public class WebweaverSwapPlugin extends Plugin
{
	static final String CONFIG_GROUP = "webweaverSwap";

	private static final Set<Integer> WEBWEAVER_BOW_IDS = Set.of(ItemID.WEBWEAVER_BOW, ItemID.WEBWEAVER_BOW_U);
	private static final int CRAWS_BOW_APPEARANCE_ID = ItemID.CRAWS_BOW + PlayerComposition.ITEM_OFFSET;
	private static final int WEBWEAVER_PROJECTILE_ID = SpotanimID.WILD_CAVE_BOW_ARROW_TRAVEL02;
	private static final int WEBWEAVER_LAUNCH_ID = SpotanimID.WILD_CAVE_BOW_ARROW_LAUNCH02;
	private static final int WEBWEAVER_IMPACT_ID = SpotanimID.FX_WEBWEAVER01_IMPACT_SPOTANIM;
	private static final int CRAWS_PROJECTILE_ID = SpotanimID.WILD_CAVE_BOW_ARROW_TRAVEL;
	private static final int CRAWS_LAUNCH_ID = SpotanimID.WILD_CAVE_BOW_ARROW_LAUNCH;
	private static final int PROJECTILE_SWAP_WINDOW = 20;
	private static final int IMPACT_GRACE_CYCLES = 2;

	private final Set<Projectile> replacedProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<PendingImpact> pendingImpacts = new ArrayList<>();
	private Set<Projectile> lastTickProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());
	private int pendingShotUntilCycle;
	private Integer originalWeaponAppearanceId;

	@Inject
	private Client client;

	@Inject
	private WebweaverSwapConfig config;

	@Provides
	WebweaverSwapConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WebweaverSwapConfig.class);
	}

	@Override
	protected void startUp()
	{
		clearAnimationState();
	}

	@Override
	protected void shutDown()
	{
		clearAnimationState();
		restoreWeaponAppearance();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		if (!config.swapCrawsBowAnimation())
		{
			clearAnimationState();
		}

		if (!config.swapCrawsBowModel())
		{
			restoreWeaponAppearance();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		updateWeaponAppearance();
		if (!config.swapCrawsBowAnimation() || !isWebweaverEquipped())
		{
			clearAnimationState();
			return;
		}

		replaceLaunchSpotanim();

		final Set<Projectile> currentTickProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());
		final Player localPlayer = client.getLocalPlayer();
		for (Projectile projectile : client.getProjectiles())
		{
			currentTickProjectiles.add(projectile);
			if (localPlayer != null && !lastTickProjectiles.contains(projectile))
			{
				replaceProjectile(projectile, localPlayer);
			}
		}

		lastTickProjectiles = currentTickProjectiles;
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		if (!config.swapCrawsBowAnimation() || !isWebweaverEquipped())
		{
			return;
		}

		final Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		replaceProjectile(event.getProjectile(), localPlayer);
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		if (!config.swapCrawsBowAnimation() || !isWebweaverEquipped())
		{
			return;
		}

		final GraphicsObject graphicsObject = event.getGraphicsObject();
		if (graphicsObject.getId() != WEBWEAVER_IMPACT_ID)
		{
			return;
		}

		prunePendingImpacts();
		final LocalPoint location = graphicsObject.getLocation();
		final int startCycle = graphicsObject.getStartCycle();
		for (Iterator<PendingImpact> it = pendingImpacts.iterator(); it.hasNext(); )
		{
			final PendingImpact pendingImpact = it.next();
			if (pendingImpact.location.equals(location)
				&& Math.abs(pendingImpact.endCycle - startCycle) <= IMPACT_GRACE_CYCLES)
			{
				graphicsObject.setFinished(true);
				it.remove();
				return;
			}
		}
	}

	private void replaceProjectile(Projectile projectile, Player localPlayer)
	{
		if (projectile.getId() != WEBWEAVER_PROJECTILE_ID
			|| !isLikelyLocalProjectile(projectile, localPlayer)
			|| !replacedProjectiles.add(projectile))
		{
			return;
		}

		final WorldPoint source = projectile.getSourcePoint();
		final WorldPoint target = projectile.getTargetPoint();
		if (source == null || target == null)
		{
			return;
		}

		final Projectile replacement = client.createProjectile(
			CRAWS_PROJECTILE_ID,
			source,
			projectile.getStartHeight(),
			null,
			target,
			projectile.getEndHeight(),
			projectile.getTargetActor(),
			projectile.getStartCycle(),
			projectile.getEndCycle(),
			projectile.getSlope(),
			projectile.getStartPos());
		if (replacement != null)
		{
			client.getProjectiles().addLast(replacement);
			lastTickProjectiles.add(replacement);
		}

		final LocalPoint impactLocation = LocalPoint.fromWorld(client.findWorldViewFromWorldPoint(target), target);
		if (impactLocation != null)
		{
			pendingImpacts.add(new PendingImpact(impactLocation, projectile.getEndCycle()));
		}

		prunePendingImpacts();
		projectile.setEndCycle(0);
	}

	private void replaceLaunchSpotanim()
	{
		final Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		for (ActorSpotAnim actorSpotAnim : localPlayer.getSpotAnims())
		{
			if (actorSpotAnim.getId() == WEBWEAVER_LAUNCH_ID)
			{
				actorSpotAnim.setId(CRAWS_LAUNCH_ID);
				actorSpotAnim.setFrame(0);
				actorSpotAnim.setCycle(0);
				pendingShotUntilCycle = client.getGameCycle() + PROJECTILE_SWAP_WINDOW;
			}
		}
	}

	private boolean isLikelyLocalProjectile(Projectile projectile, Player localPlayer)
	{
		if (projectile.getSourceActor() == localPlayer)
		{
			return true;
		}

		if (client.getGameCycle() > pendingShotUntilCycle)
		{
			return false;
		}

		final WorldPoint sourcePoint = projectile.getSourcePoint();
		final WorldPoint playerPoint = localPlayer.getWorldLocation();
		return sourcePoint != null && playerPoint != null && sourcePoint.distanceTo(playerPoint) <= 1;
	}

	private boolean isWebweaverEquipped()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null)
		{
			return false;
		}

		final Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return weapon != null && WEBWEAVER_BOW_IDS.contains(weapon.getId());
	}

	private void updateWeaponAppearance()
	{
		final Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			originalWeaponAppearanceId = null;
			return;
		}

		final PlayerComposition composition = localPlayer.getPlayerComposition();
		if (composition == null)
		{
			originalWeaponAppearanceId = null;
			return;
		}

		final int[] equipmentIds = composition.getEquipmentIds();
		final int weaponIndex = KitType.WEAPON.getIndex();
		final int currentAppearanceId = equipmentIds[weaponIndex];

		if (config.swapCrawsBowModel() && isWebweaverEquipped())
		{
			if (originalWeaponAppearanceId == null)
			{
				originalWeaponAppearanceId = currentAppearanceId;
			}

			if (equipmentIds[weaponIndex] != CRAWS_BOW_APPEARANCE_ID)
			{
				equipmentIds[weaponIndex] = CRAWS_BOW_APPEARANCE_ID;
				composition.setHash();
			}
			return;
		}

		restoreWeaponAppearance();
	}

	private void restoreWeaponAppearance()
	{
		final Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || originalWeaponAppearanceId == null)
		{
			return;
		}

		final PlayerComposition composition = localPlayer.getPlayerComposition();
		if (composition == null)
		{
			return;
		}

		final int[] equipmentIds = composition.getEquipmentIds();
		final int weaponIndex = KitType.WEAPON.getIndex();
		if (equipmentIds[weaponIndex] == CRAWS_BOW_APPEARANCE_ID)
		{
			equipmentIds[weaponIndex] = originalWeaponAppearanceId;
			composition.setHash();
		}

		originalWeaponAppearanceId = null;
	}

	private void clearAnimationState()
	{
		replacedProjectiles.clear();
		pendingImpacts.clear();
		lastTickProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());
		pendingShotUntilCycle = 0;
	}

	private void prunePendingImpacts()
	{
		final int gameCycle = client.getGameCycle();
		pendingImpacts.removeIf(pendingImpact -> pendingImpact.endCycle + IMPACT_GRACE_CYCLES < gameCycle);
	}

	private static final class PendingImpact
	{
		private final LocalPoint location;
		private final int endCycle;

		private PendingImpact(LocalPoint location, int endCycle)
		{
			this.location = location;
			this.endCycle = endCycle;
		}
	}
}
