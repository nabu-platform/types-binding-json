package be.nabu.libs.types.binding.json;

import java.nio.charset.Charset;
import java.util.Collection;

import be.nabu.libs.property.api.Property;
import be.nabu.libs.property.api.Value;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.binding.api.BindingProvider;
import be.nabu.libs.types.binding.api.DynamicBindingProvider;
import be.nabu.libs.types.binding.api.MarshallableBinding;
import be.nabu.libs.types.binding.api.UnmarshallableBinding;
import be.nabu.libs.types.map.MapTypeGenerator;

public class JSONBindingProvider implements DynamicBindingProvider {

	@Override
	public String getContentType() {
		return "application/json";
	}

	@Override
	public Collection<Property<?>> getSupportedProperties() {
		return null;
	}

	@Override
	public UnmarshallableBinding getUnmarshallableBinding(ComplexType type, Charset charset, Value<?>... values) {
		return new JSONBinding(type, charset);
	}

	@Override
	public MarshallableBinding getMarshallableBinding(ComplexType type, Charset charset, Value<?>... values) {
		return new JSONBinding(type, charset);
	}

	@Override
	public UnmarshallableBinding getDynamicUnmarshallableBinding(Charset charset, Value<?>... values) {
		JSONBinding binding = new JSONBinding(new MapTypeGenerator(), charset);
		binding.setAllowDynamicElements(true);
		binding.setAddDynamicElementDefinitions(true);
		binding.setIgnoreRootIfArrayWrapper(true);
		binding.setParseNumbers(true);
		return binding;
	}

	@Override
	public MarshallableBinding getDynamicMarshallableBinding(Charset charset, Value<?>... values) {
		JSONBinding binding = new JSONBinding(new MapTypeGenerator(), charset);
		return binding;
	}

}
