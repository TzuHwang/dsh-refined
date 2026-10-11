package com.jasoncian.millenaire_rewrite.entity.ai.work;

import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.entity.ai.MillGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.Nullable;

/**
 * 在自家建筑工作点上的工作：只在白天进行，走到工作点旁，挥动手臂一段时间后完成动作。
 * 每个村民持有自己的目标实例，所以可以保存目标位置和计时。
 */
public abstract class WorkGoal extends MillGoal {

    /** 到达后挥动手臂的时长（tick） */
    private final int workTicks;
    private final int priority;
    @Nullable
    protected BlockPos target;
    private int ticks;
    /** 到不了的工作点 -> 可以再次尝试的游戏时间 */
    private final java.util.Map<BlockPos, Long> unreachable = new java.util.HashMap<>();
    /** 到不了的工作点多久后再尝试（tick） */
    private static final int RETRY_DELAY = 1200;

    protected WorkGoal(String key, int priority, int workTicks) {
        super(key);
        this.priority = priority;
        this.workTicks = workTicks;
        this.tags.add("work");
        this.leisure = false;
        this.canBeDoneAtNight = false;
        // 可以隔着栅栏工作（原版村民也是站在围栏外砍树、采石）
        this.range = 4.0;
    }

    /**
     * 工作点是否可以尝试（最近没有因为走不到而失败）
     */
    protected boolean reachable(MillVillager villager, BlockPos pos) {
        Long retryAt = unreachable.get(pos);
        if (retryAt == null) {
            return true;
        }
        if (villager.level().getGameTime() >= retryAt) {
            unreachable.remove(pos);
            return true;
        }
        return false;
    }

    @Override
    public void onFail(MillVillager villager) {
        if (target != null) {
            unreachable.put(target, villager.level().getGameTime() + RETRY_DELAY);
        }
    }

    @Override
    public int priority(MillVillager villager) {
        return priority;
    }

    /**
     * 找到要工作的点位；没有可做的工作时返回 null
     */
    @Nullable
    protected abstract BlockPos findTarget(MillVillager villager);

    /**
     * 挥手结束后执行的动作（收成、采矿、砍树、送货）
     */
    protected abstract void work(MillVillager villager, BlockPos target);

    @Nullable
    @Override
    public GoalInformation getDestination(MillVillager villager) {
        BlockPos found = findTarget(villager);
        if (found == null) {
            return null;
        }
        target = found;
        return GoalInformation.ofDestination(WorkSites.standNear(villager.level(), found));
    }

    @Override
    public void onAccept(MillVillager villager) {
        ticks = 0;
    }

    @Override
    public boolean performAction(MillVillager villager) {
        if (target == null) {
            return true;
        }
        villager.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (ticks % 8 == 0) {
            villager.swing(InteractionHand.MAIN_HAND);
        }
        if (++ticks < workTicks) {
            return false;
        }
        work(villager, target);
        return true;
    }
}
