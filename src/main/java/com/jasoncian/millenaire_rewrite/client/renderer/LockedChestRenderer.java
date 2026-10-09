package com.jasoncian.millenaire_rewrite.client.renderer;

import com.jasoncian.millenaire_rewrite.blockentity.LockedChestBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 村庄上锁箱子的渲染器。
 *
 * 方块使用 ENTITYBLOCK_ANIMATED 渲染（为了开盖动画），没有这个渲染器时箱子是透明的。
 * 沿用原版箱子模型，暂用陷阱箱材质（红色锁扣）以区别于普通箱子。
 */
public class LockedChestRenderer implements BlockEntityRenderer<LockedChestBlockEntity> {

    private final ModelPart lid;
    private final ModelPart bottom;
    private final ModelPart lock;

    public LockedChestRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart root = context.bakeLayer(ModelLayers.CHEST);
        this.bottom = root.getChild("bottom");
        this.lid = root.getChild("lid");
        this.lock = root.getChild("lock");
    }

    @Override
    public void render(LockedChestBlockEntity chest, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BlockState state = chest.getBlockState();
        float facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            ? state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot()
            : 0.0F;

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing));
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        // 与原版 ChestRenderer 相同的开盖缓动
        float open = 1.0F - Mth.lerp(partialTick, chest.prevLidAngle, chest.lidAngle);
        open = 1.0F - open * open * open;
        lid.xRot = -(open * ((float) Math.PI / 2F));
        lock.xRot = lid.xRot;

        VertexConsumer buffer = Sheets.CHEST_TRAP_LOCATION.buffer(bufferSource, RenderType::entityCutout);
        lid.render(poseStack, buffer, packedLight, packedOverlay);
        lock.render(poseStack, buffer, packedLight, packedOverlay);
        bottom.render(poseStack, buffer, packedLight, packedOverlay);
        poseStack.popPose();
    }
}
