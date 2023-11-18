package net.ludocrypt.specialmodels.impl.access;

import java.util.function.Supplier;

import net.ludocrypt.specialmodels.impl.render.Vec4b;

public interface StateBufferBuilderAccess {

	public Supplier<Vec4b> stateAccessor();

	public void setStateAccessor(Supplier<Vec4b> stateAccessor);

}
