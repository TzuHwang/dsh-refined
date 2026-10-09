package com.jasoncian.millenaire_rewrite.client.renderer.layer;

import com.jasoncian.millenaire_rewrite.client.model.MillVillagerModel;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 村民衣服渲染层 - 在基础皮肤上渲染衣服纹理
 *
 * 与原版 LayerVillagerClothes 一致：每层使用略微膨胀的独立模型（第 0 层 +0.1，第 1 层 +0.2），
 * 像盔甲一样包在身体外面，避免和皮肤处在同一平面上产生闪烁。
 *
 * @author Based on OldSource LayerVillagerClothes
 * @version 1.0.0
 */
public class VillagerClothesLayer extends RenderLayer<MillVillager, MillVillagerModel> {

    /** 衣服层索引（0或1） */
    private final int layerIndex;

    /** 该层专用的膨胀模型 */
    private final MillVillagerModel clothModel;

    public VillagerClothesLayer(RenderLayerParent<MillVillager, MillVillagerModel> renderer, int layerIndex,
                                MillVillagerModel clothModel) {
        super(renderer);
        this.layerIndex = layerIndex;
        this.clothModel = clothModel;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       MillVillager villager, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {

        ResourceLocation clothTexture = villager.getClothTexture(layerIndex);
        if (clothTexture == null) {
            return;
        }

        // 同步身体模型的姿态（包括动画状态对四肢的调整）
        this.getParentModel().copyPropertiesTo(clothModel);

        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(clothTexture));
        clothModel.renderToBuffer(poseStack, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY,
            1.0F, 1.0F, 1.0F, 1.0F);
    }
}
