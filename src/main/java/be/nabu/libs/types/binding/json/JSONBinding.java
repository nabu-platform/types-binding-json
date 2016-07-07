package be.nabu.libs.types.binding.json;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Collection;
import java.util.Map;

import be.nabu.libs.property.api.Value;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.types.CollectionHandlerFactory;
import be.nabu.libs.types.ComplexContentWrapperFactory;
import be.nabu.libs.types.TypeUtils;
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
	
	private boolean allowDynamicElements, addDynamicElementDefinitions, ignoreUnknownElements, camelCaseDashes, camelCaseUnderscores, parseNumbers;
	private ModifiableComplexTypeGenerator complexTypeGenerator;
	private boolean ignoreRootIfArrayWrapper = false;
	
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

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public void marshal(OutputStream output, ComplexContent content, Value<?>...values) throws IOException {
		Writer writer = new OutputStreamWriter(output, charset);
		boolean alreadyWritten = false;
		if (ignoreRootIfArrayWrapper) {
			Collection<Element<?>> allChildren = TypeUtils.getAllChildren(content.getType());
			if (allChildren.size() == 1) {
				Element<?> element = allChildren.iterator().next();
				if (element.getType().isList(element.getProperties())) {
					alreadyWritten = true;
					writer.write("[");
					Object value = content.get(element.getName());
					if (value != null) {
						CollectionHandlerProvider handler = collectionHandler.getHandler(value.getClass());
						boolean isFirst = true;
						for (Object child : handler.getAsCollection(value)) {
							if (isFirst) {
								isFirst = false;
							}
							else {
								writer.write(", ");
							}
							marshal(writer, child, element);
						}
					}
					writer.write("]");
				}
			}
		}
		if (!alreadyWritten) {
			marshal(writer, content, values);
		}
		writer.flush();
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void marshal(Writer writer, ComplexContent content, Value<?>...values) throws IOException {
		writer.write("{");
		boolean isFirst = true;
		if (content.getType() == null) {
			throw new NullPointerException("No complex type found for: " + content.getClass().getName());
		}
		for (Element<?> element : TypeUtils.getAllChildren((ComplexType) content.getType())) {
			Object value = content.get(element.getName());
			if (element.getType().isList(element.getProperties())) {
				// only write the list if the value is not null or we explicitly enable the "writeEmptyLists" boolean
				if (value != null) {
					if (isFirst) {
						isFirst = false;
					}
					else {
						writer.write(", ");
					}
					boolean isFirstChild = true;
					if (value instanceof Map) {
						writer.write("\"" + element.getName() + "\": {");
						for (Object key : ((Map) value).keySet()) {
							if (isFirstChild) {
								isFirstChild = false;
							}
							else {
								writer.write(", ");
							}
							writer.write("\"" + key.toString() + "\": ");
							marshal(writer, ((Map) value).get(key), element);
						}
						writer.write("}");
					}
					else {
						CollectionHandlerProvider handler = collectionHandler.getHandler(value.getClass());
						writer.write("\"" + element.getName() + "\": [");
						if (value != null) {
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
				}
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
		if (value == null) {
			writer.write("null");
		}
		else if (element.getType() instanceof ComplexType) {
			if (!(value instanceof ComplexContent)) {
				Object converted = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(value);
				if (converted == null) {
					throw new ClassCastException("Can not convert " + value + " in " + element.getParent() + " to a complex content");
				}
				else {
					value = converted;
				}
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
					.replace("\n", "\\n").replaceAll("\r", "").replace("\t", "\\t").replace("/", "\\/");
				writer.write("\"" + marshalledValue + "\"");
			}
		}
	}
	
	@Override
	protected ComplexContent unmarshal(ReadableResource resource, Window[] windows, Value<?>... values) throws IOException, ParseException {
		ReadableContainer<CharBuffer> readable = IOUtils.wrapReadable(resource.getReadable(), charset);
		JSONUnmarshaller jsonUnmarshaller = new JSONUnmarshaller();
		jsonUnmarshaller.setIgnoreRootIfArrayWrapper(ignoreRootIfArrayWrapper);
		jsonUnmarshaller.setAddDynamicElementDefinitions(isAddDynamicElementDefinitions());
		jsonUnmarshaller.setAllowDynamicElements(isAllowDynamicElements());
		jsonUnmarshaller.setIgnoreUnknownElements(ignoreUnknownElements);
		jsonUnmarshaller.setComplexTypeGenerator(getComplexTypeGenerator());
		jsonUnmarshaller.setCamelCaseDashes(camelCaseDashes);
		jsonUnmarshaller.setCamelCaseUnderscores(camelCaseUnderscores);
		jsonUnmarshaller.setParseNumbers(parseNumbers);
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

	public boolean isIgnoreRootIfArrayWrapper() {
		return ignoreRootIfArrayWrapper;
	}

	public void setIgnoreRootIfArrayWrapper(boolean ignoreRootIfArrayWrapper) {
		this.ignoreRootIfArrayWrapper = ignoreRootIfArrayWrapper;
	}

	public boolean isIgnoreUnknownElements() {
		return ignoreUnknownElements;
	}

	public void setIgnoreUnknownElements(boolean ignoreUnknownElements) {
		this.ignoreUnknownElements = ignoreUnknownElements;
	}

	public boolean isCamelCaseDashes() {
		return camelCaseDashes;
	}

	public void setCamelCaseDashes(boolean camelCaseDashes) {
		this.camelCaseDashes = camelCaseDashes;
	}

	public boolean isCamelCaseUnderscores() {
		return camelCaseUnderscores;
	}

	public void setCamelCaseUnderscores(boolean camelCaseUnderscores) {
		this.camelCaseUnderscores = camelCaseUnderscores;
	}

	public boolean isParseNumbers() {
		return parseNumbers;
	}

	public void setParseNumbers(boolean parseNumbers) {
		this.parseNumbers = parseNumbers;
	}

}
