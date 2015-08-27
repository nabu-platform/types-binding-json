package be.nabu.libs.types.binding.json;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.ParseException;

import be.nabu.libs.property.api.Value;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.types.CollectionHandlerFactory;
import be.nabu.libs.types.ComplexContentWrapperFactory;
import be.nabu.libs.types.api.CollectionHandler;
import be.nabu.libs.types.api.CollectionHandlerProvider;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.Element;
import be.nabu.libs.types.api.Marshallable;
import be.nabu.libs.types.api.ModifiableComplexTypeGenerator;
import be.nabu.libs.types.binding.BaseTypeBinding;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.CharBuffer;
import be.nabu.utils.io.api.ReadableContainer;

public class JSONBinding extends BaseTypeBinding {

	private Charset charset;
	private CollectionHandler collectionHandler = CollectionHandlerFactory.getInstance().getHandler();
	private ComplexType type;
	
	private boolean allowDynamicElements, addDynamicElementDefinitions;
	private ModifiableComplexTypeGenerator complexTypeGenerator;
	
	public JSONBinding(ModifiableComplexTypeGenerator complexTypeGenerator, Charset charset) {
		this(complexTypeGenerator.newComplexType(), charset);
		this.complexTypeGenerator = complexTypeGenerator;
	}
	
	public JSONBinding(ComplexType type, Charset charset) {
		this.type = type;
		this.charset = charset;
	}
	
	public JSONBinding(ComplexType type) {
		this(type, Charset.forName("UTF-8"));
	}

	@Override
	public void marshal(OutputStream output, ComplexContent content, Value<?>...values) throws IOException {
		Writer writer = new OutputStreamWriter(output, charset);
		marshal(writer, content, values);
		writer.flush();
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void marshal(Writer writer, ComplexContent content, Value<?>...values) throws IOException {
		writer.write("{");
		boolean isFirst = true;
		for (Element<?> element : (ComplexType) content.getType()) {
			Object value = content.get(element.getName());
			if (element.getType().isList(element.getProperties())) {
				if (isFirst) {
					isFirst = false;
				}
				else {
					writer.write(", ");
				}
				writer.write("\"" + element.getName() + "\": [");
				if (value != null) {
					CollectionHandlerProvider handler = collectionHandler.getHandler(value.getClass());
					boolean isFirstChild = true;
					for (Object child : handler.getAsCollection(value)) {
						if (isFirstChild) {
							isFirstChild = false;
						}
						else {
							writer.write(", ");
						}
						marshal(writer, child, element);
					}
				}
				writer.write("]");
			}
			// only write a non-list value if it is not null
			else if (value != null) {
				if (isFirst) {
					isFirst = false;
				}
				else {
					writer.write(", ");
				}
				writer.write("\"" + element.getName() + "\": ");
				marshal(writer, value, element);
			}
		}
		writer.write("}");
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void marshal(Writer writer, Object value, Element<?> element) throws IOException {
		if (element.getType() instanceof ComplexType) {
			if (!(value instanceof ComplexContent)) {
				value = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(value);
			}
			marshal(writer, (ComplexContent) value, element.getProperties());
		}
		else {
			if (value instanceof Boolean || value instanceof Number) {
				writer.write(value.toString());
			}
			// everything else has to be stringified
			else {
				String marshalledValue = ((Marshallable) element.getType()).marshal(value, element.getProperties());
				// escape
				marshalledValue = marshalledValue.replace("\\", "\\\\").replace("\"", "\\\"")
					.replace("\n", "\\n").replaceAll("\r", "");
				writer.write("\"" + marshalledValue + "\"");
			}
		}
	}
	
	@Override
	protected ComplexContent unmarshal(ReadableResource resource, Window[] windows, Value<?>... values) throws IOException, ParseException {
		ReadableContainer<CharBuffer> readable = IOUtils.wrapReadable(resource.getReadable(), charset);
		JSONUnmarshaller jsonUnmarshaller = new JSONUnmarshaller();
		jsonUnmarshaller.setAddDynamicElementDefinitions(isAddDynamicElementDefinitions());
		jsonUnmarshaller.setAllowDynamicElements(isAllowDynamicElements());
		jsonUnmarshaller.setComplexTypeGenerator(getComplexTypeGenerator());
		return jsonUnmarshaller.unmarshal(readable, type);
	}

	public boolean isAllowDynamicElements() {
		return allowDynamicElements;
	}

	public void setAllowDynamicElements(boolean allowDynamicElements) {
		this.allowDynamicElements = allowDynamicElements;
	}

	public boolean isAddDynamicElementDefinitions() {
		return addDynamicElementDefinitions;
	}

	public void setAddDynamicElementDefinitions(boolean addDynamicElementDefinitions) {
		this.addDynamicElementDefinitions = addDynamicElementDefinitions;
	}

	public ModifiableComplexTypeGenerator getComplexTypeGenerator() {
		return complexTypeGenerator;
	}

	public void setComplexTypeGenerator(ModifiableComplexTypeGenerator complexTypeGenerator) {
		this.complexTypeGenerator = complexTypeGenerator;
	}
}
