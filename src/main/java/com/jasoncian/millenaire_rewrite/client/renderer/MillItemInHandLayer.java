package com.jasoncian.millenaire_rewrite.client.renderer;

import com.jasoncian.millenaire_rewrite.client.model.MillVillagerModel;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 村民手持物品：搬运动作会把双手抬到胸前，方块按第三人称大小画会挡住整个身体，所以方块缩小一些
 */
public class MillItemInHandLayer extends ItemInHandLayer<MillVillager, MillVillagerModel> {

    /** 手持方块的额外缩放 */
    private static final float BLOCK_SCALE = 0.6F;

    private final ItemInHandRenderer itemInHandRenderer;

    public MillItemInHandLayer(RenderLayerParent<MillVillager, MillVillagerModel> parent, ItemInHandRenderer renderer) {
        super(parent, renderer);
        this.itemInHandRenderer = renderer;
    }

    @Override
    protected void renderArmWithItem(LivingEntity entity, ItemStack stack, ItemDisplayContext context, HumanoidArm arm,
                                     PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (stack.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        this.getParentModel().translateToHand(arm, poseStack);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        boolean left = arm == HumanoidArm.LEFT;
        poseStack.translate((left ? -1 : 1) / 16.0F, 0.125F, -0.625F);
        if (stack.getItem() instanceof BlockItem) {
            poseStack.scale(BLOCK_SCALE, BLOCK_SCALE, BLOCK_SCALE);
        }
        this.itemInHandRenderer.renderItem(entity, stack, context, left, poseStack, buffer, packedLight);
        poseStack.popPose();
    }
}
