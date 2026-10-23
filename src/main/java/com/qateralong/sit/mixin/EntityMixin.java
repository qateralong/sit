package com.qateralong.sit.mixin;

import com.qateralong.sit.Sit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getDismountLocationForPassenger", at = @At("HEAD"), cancellable = true)
    private void sit$dismount(LivingEntity passenger, CallbackInfoReturnable<Vec3> cir) {
        Entity self = (Entity) (Object) this;
        if (Sit.isSeat(self)) {
            cir.setReturnValue(Sit.standUpPosition(self, passenger));
        } else if (self instanceof Player && passenger instanceof Player) {
            cir.setReturnValue(Sit.besidePosition(self, passenger));
        }
    }
}
