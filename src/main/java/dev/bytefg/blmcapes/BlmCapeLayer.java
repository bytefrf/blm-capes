package dev.bytefg.blmcapes;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.Items;

public final class BlmCapeLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    public BlmCapeLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                       float limbSwing, float limbAmount, float partialTick, float age, float yaw, float pitch) {
        CapeAssignment assignment = assignmentFor(player);
        ResourceLocation cape = textureFor(assignment);
        if (cape == null || player.isInvisible()
                || !player.isModelPartShown(PlayerModelPart.CAPE)
                || player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                || (player.isCapeLoaded() && player.getCloakTextureLocation() != null)) return;

        pose.pushPose();
        pose.translate(0.0F, 0.0F, 0.125F);
        double dx = Mth.lerp(partialTick, player.xCloakO, player.xCloak) - Mth.lerp(partialTick, player.xo, player.getX());
        double dy = Mth.lerp(partialTick, player.yCloakO, player.yCloak) - Mth.lerp(partialTick, player.yo, player.getY());
        double dz = Mth.lerp(partialTick, player.zCloakO, player.zCloak) - Mth.lerp(partialTick, player.zo, player.getZ());
        float rotation = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        double sin = Mth.sin(rotation * Mth.DEG_TO_RAD);
        double cos = -Mth.cos(rotation * Mth.DEG_TO_RAD);
        float vertical = Mth.clamp((float) dy * 10.0F, -6.0F, 32.0F);
        float forward = Mth.clamp((float) (dx * sin + dz * cos) * 100.0F, 0.0F, 150.0F);
        float side = Mth.clamp((float) (dx * cos - dz * sin) * 100.0F, -20.0F, 20.0F);
        float bob = Mth.lerp(partialTick, player.oBob, player.bob);
        vertical += Mth.sin(Mth.lerp(partialTick, player.walkDistO, player.walkDist) * 6.0F) * 32.0F * bob;
        if (player.isCrouching()) vertical += 25.0F;
        pose.mulPose(Axis.XP.rotationDegrees(6.0F + forward / 2.0F + vertical));
        pose.mulPose(Axis.ZP.rotationDegrees(side / 2.0F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - side / 2.0F));
        getParentModel().renderCloak(pose, buffers.getBuffer(RenderType.entitySolid(cape)), light,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static CapeAssignment assignmentFor(AbstractClientPlayer player) {
        String debugCape = CapeConfig.getDebugCape(player.getUUID());
        return debugCape == null ? CapeSyncManager.getAssignment(player.getUUID()) : CapeProtocol.assignmentFor(debugCape);
    }

    private static ResourceLocation textureFor(CapeAssignment assignment) {
        if (assignment == null) return null;
        if (assignment.isCustom()) return CustomCapeTextures.getTexture(assignment.customHash());
        return new ResourceLocation(BlmCapes.MOD_ID, "textures/capes/" + assignment.builtinId() + ".png");
    }
}
