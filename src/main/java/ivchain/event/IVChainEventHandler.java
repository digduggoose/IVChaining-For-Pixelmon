package ivchain.event;

import com.google.common.collect.Lists;
import com.pixelmonmod.pixelmon.api.events.BattleStartedEvent;
import com.pixelmonmod.pixelmon.api.events.BeatWildPixelmonEvent;
import com.pixelmonmod.pixelmon.api.events.CaptureEvent;
import com.pixelmonmod.pixelmon.battles.controller.participants.BattleParticipant;
import com.pixelmonmod.pixelmon.battles.controller.participants.PixelmonWrapper;
import com.pixelmonmod.pixelmon.battles.controller.participants.PlayerParticipant;
import com.pixelmonmod.pixelmon.battles.controller.participants.WildPixelmonParticipant;
import com.pixelmonmod.pixelmon.entities.pixelmon.EntityPixelmon;
import com.pixelmonmod.pixelmon.entities.pixelmon.stats.IVStore;
import com.pixelmonmod.pixelmon.entities.pixelmon.stats.StatsType;
import com.pixelmonmod.pixelmon.worldGeneration.dimension.ultraspace.UltraSpace;
import ivchain.IVChain;
import ivchain.IVConfig;
import ivchain.capability.CapabilityChainTracker;
import ivchain.capability.IChainTracker;
import ivchain.util.ChainingHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;

public class IVChainEventHandler {
    public static class ForgeHandler {
        @SubscribeEvent
        public void attachCapability(AttachCapabilitiesEvent<Entity> evt) {
            if (evt.getObject() instanceof EntityPlayerMP) {
                evt.addCapability(ChainingHandler.CHAINING_LOCATION, new ICapabilitySerializable<NBTTagCompound>() {
                    IChainTracker tracker = CapabilityChainTracker.CHAIN_TRACKER.getDefaultInstance();

                    @Override
                    public boolean hasCapability(Capability<?> cap, EnumFacing facing) {
                        return cap == CapabilityChainTracker.CHAIN_TRACKER;
                    }

                    @Override
                    public <T> T getCapability(Capability<T> cap, EnumFacing facing) {
                        return cap == CapabilityChainTracker.CHAIN_TRACKER ? CapabilityChainTracker.CHAIN_TRACKER.cast(tracker) : null;
                    }

                    @Override
                    public NBTTagCompound serializeNBT() {
                        return ((NBTTagCompound) CapabilityChainTracker.CHAIN_TRACKER.getStorage().writeNBT(CapabilityChainTracker.CHAIN_TRACKER, tracker, null));
                    }

                    @Override
                    public void deserializeNBT(NBTTagCompound tag) {
                        CapabilityChainTracker.CHAIN_TRACKER.getStorage().readNBT(CapabilityChainTracker.CHAIN_TRACKER, tracker, null, tag);
                    }
                });
            }
        }

        @SubscribeEvent
        public void onPlayerClone(PlayerEvent.Clone evt) {
            if (!evt.isWasDeath() && evt.getEntityPlayer() instanceof EntityPlayerMP && evt.getOriginal() instanceof EntityPlayerMP) {
                NBTTagCompound tag = new NBTTagCompound();
                ChainingHandler.getPlayer((EntityPlayerMP) evt.getOriginal()).writeNBTValue(tag);
                ChainingHandler.getPlayer((EntityPlayerMP) evt.getEntityPlayer()).readNBTValue(tag);
            }
        }
    }

    public static class PixelHandler {
        private static final StatsType[] STATS_TYPES = {StatsType.HP, StatsType.Attack, StatsType.Defence, StatsType.SpecialAttack, StatsType.SpecialDefence, StatsType.Speed};

        @SubscribeEvent
        public void onPixelmonEncountered(BattleStartedEvent evt) {
            if (!IVConfig.chainHA) return;
            //Ensure that this is a PLAYER-WILD BATTLE
            if (evt.bc.containsParticipantType(WildPixelmonParticipant.class) && evt.bc.containsParticipantType(PlayerParticipant.class)) {
                BattleParticipant wild = evt.participant1[0] instanceof WildPixelmonParticipant ? evt.participant1[0] : evt.participant2[0];
                EntityPlayerMP player = evt.bc.getPlayers().get(0).player;
                PixelmonWrapper pixel = wild.controlledPokemon.get(0);
                String name = pixel.getPokemonName();
                if (getPlayer(player).getChainName().matches(name)) {
                    if (pixel.pokemon.getAbilitySlot() != 2 && canHiddenAbility(player))
                    wild.controlledPokemon.get(0).pokemon.setAbilitySlot(2);
                }
            }
        }

        @SubscribeEvent
        public void onPixelmonDefeat(BeatWildPixelmonEvent evt) {
            WildPixelmonParticipant pixelmon = evt.wpp;
            String name = pixelmon.controlledPokemon.get(0).getPokemonName();
            EntityPlayerMP player = evt.player;
            advanceChain(player, name);
        }

        @SubscribeEvent
        public void onPixelmonCatch(CaptureEvent.SuccessfulCapture evt) {
            EntityPixelmon pixelmon = evt.getPokemon();
            String name = pixelmon.getPokemonName();
            EntityPlayerMP player = evt.player;
            advanceChain(player, name);
            if (getPlayer(player) != null) {
                byte chain = getPlayer(player).getChainValue();
                int guaranteedIVs = chain > 29 ? 4 : chain > 19 ? 3 : chain > 9 ? 2 : chain > 4 ? 1 : 0;
                if (guaranteedIVs > 0) {
                    List<StatsType> types = Lists.newArrayList();

                    for (StatsType type : STATS_TYPES) {
                        //If we want to skip already-perfect IVs, then we just don't add them to the list.
                        if (IVConfig.easyMode && pixelmon.getPokemonData().getStats().ivs.get(type) == IVStore.MAX_IVS)
                            continue;
                        types.add(type);
                    } //If all the IVs are perfect, then this isn't worth going through.
                    if (types.isEmpty()) return;
                    for (int i = 0; i < guaranteedIVs && !types.isEmpty(); i++) {
                        int place = types.size() == 1 ? 0 : IVChain.instance.rand.nextInt(types.size());
                        pixelmon.getPokemonData().getStats().ivs.set(types.get(place), IVStore.MAX_IVS);
                        types.remove(place);
                    }
                    evt.setPokemon(pixelmon);
                }
            }
        }

        private boolean canHiddenAbility(EntityPlayerMP player) {
            byte chain = getPlayer(player).getChainValue();
            int chance = chain < 10 ? 0 : chain < 20 ? 5 : chain < 30 ? 10 : 15;
            return chance > 0 && IVChain.instance.rand.nextInt(isUltraSpace(player) ? 50 : 100) < chance;
        }

        private void advanceChain(EntityPlayerMP player, String pixelmonName) {
            if (getPlayer(player) != null) {
                String chain = getPlayer(player).getChainName();
                boolean continuesChain = pixelmonName.equals(chain);
                getPlayer(player).incrementChainValue(!continuesChain);
                if (!continuesChain) {
                    getPlayer(player).setChainName(pixelmonName);
                }
            }
        }

        private IChainTracker getPlayer(EntityPlayerMP player) {
            return player.getCapability(CapabilityChainTracker.CHAIN_TRACKER, EnumFacing.UP);
        }

        private boolean isUltraSpace(EntityPlayerMP player) {
            return player.dimension == UltraSpace.DIM_ID;
        }
    }
}
