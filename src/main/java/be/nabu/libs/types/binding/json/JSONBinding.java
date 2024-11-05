package be.nabu.libs.types.binding.json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Collection;
import java.util.Map;

import org.slf4j.LoggerFactory;

import be.nabu.libs.converter.ConverterFactory;
import be.nabu.libs.property.ValueUtils;
import be.nabu.libs.property.api.Value;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.types.BaseTypeInstance;
import be.nabu.libs.types.CollectionHandlerFactory;
import be.nabu.libs.types.ComplexContentWrapperFactory;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.CollectionHandler;
import be.nabu.libs.types.api.CollectionHandlerProvider;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedSimpleType;
import be.nabu.libs.types.api.Element;
import be.nabu.libs.types.api.KeyValuePair;
import be.nabu.libs.types.api.MarshalException;
import be.nabu.libs.types.api.Marshallable;
import be.nabu.libs.types.api.ModifiableComplexTypeGenerator;
import be.nabu.libs.types.api.TypeInstance;
import be.nabu.libs.types.base.SimpleElementImpl;
import be.nabu.libs.types.binding.BaseTypeBinding;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.java.BeanResolver;
import be.nabu.libs.types.java.BeanType;
import be.nabu.libs.types.properties.AliasProperty;
import be.nabu.libs.types.properties.DynamicNameProperty;
import be.nabu.libs.types.properties.MatrixProperty;
import be.nabu.libs.types.properties.MinOccursProperty;
import be.nabu.utils.codec.TranscoderUtils;
import be.nabu.utils.codec.impl.Base64Encoder;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.CharBuffer;
import be.nabu.utils.io.api.ReadableContainer;

public class JSONBinding extends BaseTypeBinding {

	private Charset charset;
	private CollectionHandler collectionHandler = CollectionHandlerFactory.getInstance().getHandler();
	private ComplexType type;
	
	// @2024-06-29 I updated setEmptyArrays to true so the parser (by default) better reflects the actual data coming in
	private boolean allowDynamicElements, addDynamicElementDefinitions, ignoreUnknownElements, camelCaseDashes, camelCaseUnderscores, parseNumbers, allowRaw, setEmptyArrays = true, ignoreEmptyStrings, expandKeyValuePairs, useAlias = true, addDynamicStringsOnly;
	private ModifiableComplexTypeGenerator complexTypeGenerator;
	private boolean ignoreRootIfArrayWrapper = false;
	private boolean prettyPrint, ignoreInconsistentTypes;
	private boolean ignoreDynamicNames;
	private boolean allowNilCharacter;
	private boolean marshalNonExistingRequiredFields = true;
	private boolean marshalStreams = true;
	
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
					if (prettyPrint) {
						writer.write("\n");
					}
					Object value = content.get(element.getName());
					if (value != null) {
						CollectionHandlerProvider handler = collectionHandler.getHandler(value.getClass());
						if (handler != null) {
							value = handler.getAsIterable(value);
						}
						else if (!(value instanceof Iterable)) {
							throw new IllegalArgumentException("Can not find collection handler for " + value.getClass() + ": " + value);
						}
						boolean isFirst = true;
						for (Object child : (Iterable) value) {
							if (isFirst) {
								isFirst = false;
							}
							else {
								writer.write(", ");
							}
							marshal(writer, child, element, 0);
						}
					}
					if (prettyPrint) {
						writer.write("\n");
					}
					writer.write("]");
				}
			}
		}
		if (!alreadyWritten) {
			marshal(writer, content, 0, values);
		}
		writer.flush();
	}
	
	private void printDepth(Writer writer, int depth) throws IOException {
		for (int i = 0; i < depth; i++) {
			writer.write("\t");
		}
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void marshal(Writer writer, ComplexContent content, int depth, Value<?>...values) throws IOException {
		// check if it's a matrix
		Boolean matrix = ValueUtils.getValue(MatrixProperty.getInstance(), values);
		if (matrix == null || !matrix) {
			writer.write("{");
			if (prettyPrint) {
				writer.write("\n");
			}
		}
		boolean isFirst = true;
		if (content.getType() == null) {
			throw new NullPointerException("No complex type found for: " + content.getClass().getName());
		}
		String dynamicKey = ignoreDynamicNames ? null : ValueUtils.getValue(DynamicNameProperty.getInstance(), values);
		TypeInstance keyValueInstance = new BaseTypeInstance(BeanResolver.getInstance().resolve(KeyValuePair.class));
		for (Element<?> element : TypeUtils.getAllChildren((ComplexType) content.getType())) {
			// by default we don't print the dynamic keys
			if (dynamicKey != null && element.getName().equals(dynamicKey)) {
				continue;
			}
			Object value = content.get(element.getName());
			Value<String> alias = useAlias ? element.getProperty(AliasProperty.getInstance()) : null;
			// @2024-08-12: we only checked if the element itself was a list, however a singular object value might still represent a list at runtime
			// e.g. in the diff routines in CDM
			boolean isList = element.getType().isList(element.getProperties());
			boolean isObject = element.getType() instanceof BeanType && ((BeanType) element.getType()).getBeanClass().equals(java.lang.Object.class);
			if (isObject && !isList) {
				isList = value != null && (value instanceof Map || collectionHandler.getHandler(value.getClass()) != null);
			}
			if (isList) {
				// only write the list if the value is not null or we explicitly enable the "writeEmptyLists" boolean
				if (value != null) {
					if (isFirst) {
						isFirst = false;
					}
					else {
						writer.write(", ");
						if (prettyPrint) {
							writer.write("\n");
						}
					}
					boolean isFirstChild = true;
					if (value instanceof Map) {
						writer.write("\"" + (alias == null ? element.getName() : alias.getValue()) + "\": {");
						for (Object key : ((Map) value).keySet()) {
							if (isFirstChild) {
								isFirstChild = false;
							}
							else {
								writer.write(", ");
							}
							if (prettyPrint) {
								writer.write("\n");
								printDepth(writer, depth + 1);
							}
							writer.write("\"" + key + "\": ");
							marshal(writer, ((Map) value).get(key), element, depth);
						}
						if (prettyPrint) {
							writer.write("\n");
							printDepth(writer, depth);
						}
						writer.write("}");
					}
					else {
						// we have to always use the collection handler, glue has "CollectionIterable" which _are_ iterables but also lazy
						CollectionHandlerProvider handler = collectionHandler.getHandler(value.getClass());
						if (handler != null) {
							value = handler.getAsIterable(value);
						}
						else if (!(value instanceof Iterable)) {
							throw new IllegalArgumentException("Can not find collection handler for " + value.getClass() + ": " + value);
						}
						Value<String> dynamicName = ignoreDynamicNames ? null : element.getProperty(DynamicNameProperty.getInstance());
						if (dynamicName != null && dynamicName.getValue() != null) {
							boolean isFirstDynamic = true;
							for (Object child : (Iterable) value) {
								if (isFirstDynamic) {
									isFirstDynamic = false;
								}
								else {
									writer.write(", ");
									if (prettyPrint) {
										writer.write("\n");
									}
								}
								if (prettyPrint) {
									printDepth(writer, depth + 1);
								}
								if (!(child instanceof ComplexContent)) {
									child = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(child);
								}
								if (child != null) {
									Object key = ((ComplexContent) child).get(dynamicName.getValue());
									if (key != null && !(key instanceof String)) {
										key = ConverterFactory.getInstance().getConverter().convert(key, String.class);
									}
									if (key != null) {
										writer.write("\"" + key + "\": ");
										marshal(writer, (ComplexContent) child, depth + 1, element.getProperties());
									}
								}
							}
						}
						else if (expandKeyValuePairs && TypeUtils.isSubset(new BaseTypeInstance(element.getType()), keyValueInstance)) {
							boolean isFirstKeyValuePair = true;
							for (Object child : (Iterable) value) {
								if (isFirstKeyValuePair) {
									isFirstKeyValuePair = false;
								}
								else {
									writer.write(", ");
									if (prettyPrint) {
										writer.write("\n");
									}
								}
								if (prettyPrint) {
									printDepth(writer, depth + 1);
								}
								if (!(child instanceof ComplexContent)) {
									child = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(child);
								}
								String propertyKey = (String) ((ComplexContent) child).get("key");
								String propertyValue = (String) ((ComplexContent) child).get("value");
								writer.write("\"" + propertyKey + "\": ");
								Element expectedElement = new SimpleElementImpl(propertyKey, SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(String.class), (ComplexType) content.getType());
								marshal(writer, propertyValue, expectedElement, depth + 1);
							}
						}
						else {
							if (prettyPrint) {
								printDepth(writer, depth + 1);
							}
							if (matrix != null && matrix) {
								writer.write("[");
							}
							else {
								writer.write("\"" + (alias == null ? element.getName() : alias.getValue()) + "\": [");
							}
							boolean hasContent = false;
							for (Object child : (Iterable) value) {
								if (prettyPrint && !hasContent) {
									hasContent = true;
									writer.write("\n");
								}
								if (isFirstChild) {
									isFirstChild = false;
								}
								else {
									writer.write(", ");
									if (prettyPrint) {
										writer.write("\n");
									}
								}
								if (prettyPrint) {
									printDepth(writer, depth + 2);
								}
								marshal(writer, child, element, depth + 1);
							}
							if (prettyPrint && hasContent) {
								writer.write("\n");
								printDepth(writer, depth + 1);
							}
							writer.write("]");
						}
					}
				}
				else if (marshalNonExistingRequiredFields) {
					Value<Integer> minOccurs = element.getProperty(MinOccursProperty.getInstance());
					if (minOccurs == null || minOccurs.getValue() > 0) {
						if (isFirst) {
							isFirst = false;
						}
						else {
							writer.write(", ");
							if (prettyPrint) {
								writer.write("\n");
							}
						}
						if (prettyPrint) {
							printDepth(writer, depth);
						}
						writer.write("\"" + (alias == null ? element.getName() : alias.getValue()) + "\": []");
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
					if (prettyPrint) {
						writer.write("\n");
					}
				}
				if (prettyPrint) {
					printDepth(writer, depth + 1);
				}
				if (matrix != null && matrix) {
					// do nothing?
				}
				else {
					writer.write("\"" + (alias == null ? element.getName() : alias.getValue()) + "\": ");
				}
				marshal(writer, value, element, depth);
			}
			else if (marshalNonExistingRequiredFields) {
				Value<Integer> minOccurs = element.getProperty(MinOccursProperty.getInstance());
				if (minOccurs == null || minOccurs.getValue() > 0) {
					if (isFirst) {
						isFirst = false;
					}
					else {
						writer.write(", ");
						if (prettyPrint) {
							writer.write("\n");
						}
					}
					if (prettyPrint) {
						printDepth(writer, depth + 1);
					}
					if (matrix != null && matrix) {
						// do nothing?
					}
					else {
						writer.write("\"" + (alias == null ? element.getName() : alias.getValue()) + "\": null");
					}
				}
			}
		}
		if (matrix == null || !matrix) {
			if (prettyPrint) {
				writer.write("\n");
				printDepth(writer, depth);
			}
			writer.write("}");
		}
	}

	@SuppressWarnings({ "unchecked" })
	private void marshal(Writer writer, Object value, Element<?> element, int depth) throws IOException {
		try {
			if (value == null) {
				writer.write("null");
			}
			else if (element.getType() instanceof ComplexType) {
				Marshallable<?> marshallable = null;
				// if we have an object, check if it is not secretly a simple type
				if (element.getType() instanceof BeanType && ((BeanType<?>) element.getType()).getBeanClass().equals(Object.class)) {
					DefinedSimpleType<? extends Object> wrap = SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(value.getClass());
					if (wrap != null) {
						marshallable = (Marshallable<?>) wrap;
					}
				}
				if (marshallable != null) {
					marshalSimpleValue(writer, value, (Marshallable<?>) marshallable);
				}
				else {
					if (!(value instanceof ComplexContent)) {
						Object converted = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(value);
						if (converted == null) {
							throw new ClassCastException("Can not convert " + value + " in " + element.getParent() + " to a complex content");
						}
						else {
							value = converted;
						}
					}
					marshal(writer, (ComplexContent) value, depth + 1, element.getProperties());
				}
			}
			else if (!(element.getType() instanceof Marshallable)) {
				if ((value instanceof InputStream && marshalStreams) || value instanceof byte[]) {
					if (value instanceof byte[]) {
						value = new ByteArrayInputStream((byte[]) value);
					}
					ReadableContainer<ByteBuffer> transcodedBytes = TranscoderUtils.transcodeBytes(IOUtils.wrap((InputStream) value), new Base64Encoder());
					String marshalledValue = IOUtils.toString(IOUtils.wrapReadable(transcodedBytes, Charset.forName("ASCII")));
					marshalledValue = escape(marshalledValue, allowRaw, allowNilCharacter);
					writer.write("\"" + marshalledValue + "\"");
				}
				else {
					throw new MarshalException("The simple value for " + value + " can not be marshalled");
				}
			}
			else {
				marshalSimpleValue(writer, value, (Marshallable<?>) element.getType(), element.getProperties());
			}
		}
		catch (RuntimeException e) {
			throw new RuntimeException("Could not marshal element '" + element.getName() + "' with value: " + value, e);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void marshalSimpleValue(Writer writer, Object value, Marshallable type, Value<?>...properties) throws IOException {
		if (value instanceof Boolean || value instanceof Number) {
			writer.write(value.toString());
		}
		// everything else has to be stringified
		else {
			String marshalledValue = value instanceof String ? (String) value : type.marshal(value, properties);
			marshalledValue = escape(marshalledValue, allowRaw, allowNilCharacter);
//			if (!allowRaw) {
//				// escape
//				marshalledValue = marshalledValue.replace("\\", "\\\\").replace("\"", "\\\"")
//					.replace("\n", "\\n").replaceAll("\r", "").replace("\t", "\\t").replace("/", "\\/");
//			}
//			// even in raw mode, we need to escape some stuff
//			else {
//				marshalledValue = marshalledValue.replace("\\", "\\\\").replace("\"", "\\\"")
//						.replace("\n", "\\n").replaceAll("\r", "").replace("\t", "\\t");
//			}
			writer.write("\"" + marshalledValue + "\"");
		}
	}
	
	public static String escape(String content, boolean raw, boolean allowNilCharacter) {
		StringBuilder builder = new StringBuilder();
		char previous = 0;
		for (int i = 0; i < content.length(); i++) {
			char current = content.charAt(i);
			switch(current) {
				case '\\':
				case '"':
					builder.append('\\');
					builder.append(current);
				break;
				// if we don't allow raw, escape this as well
				// based on jettison, we also escape the forward slash if it follows an opening fishtag (html/xml shizzles)
				case '/':
					if (!raw || previous == '<') {
						builder.append('\\');
					}
					builder.append(current);	
				break;
				case '\r':
					builder.append("\\r");
				break;
				case '\n':
					builder.append("\\n");
				break;
				case '\t':
					builder.append("\\t");
				break;
				case '\b':
					builder.append("\\b");
				break;
				case '\f':
					builder.append("\\f");
				break;
				default:
					// we must encode characters under 20, but anything below 32 is basically a control character
					// the "interesting" control characters are already covered in the above
					if (current == 0 && !allowNilCharacter) {
						LoggerFactory.getLogger(JSONBinding.class).warn("Skipping 0 byte in JSON content: " + content);
					}
					else if (current < 32) {
						String hex = "000" + Integer.toHexString(current);
						builder.append("\\u" + hex.substring(hex.length() - 4));
					}
					else {
						builder.append(current);
					}
			}
			previous = current;
		}
		return builder.toString();
	}
	
	@Override
	protected ComplexContent unmarshal(ReadableResource resource, Window[] windows, Value<?>... values) throws IOException, ParseException {
		ReadableContainer<CharBuffer> readable = IOUtils.wrapReadable(resource.getReadable(), charset);
		JSONUnmarshaller jsonUnmarshaller = new JSONUnmarshaller();
		jsonUnmarshaller.setIgnoreInconsistentTypes(ignoreInconsistentTypes);
		jsonUnmarshaller.setIgnoreRootIfArrayWrapper(ignoreRootIfArrayWrapper);
		jsonUnmarshaller.setAddDynamicElementDefinitions(isAddDynamicElementDefinitions());
		jsonUnmarshaller.setAllowDynamicElements(isAllowDynamicElements());
		jsonUnmarshaller.setIgnoreUnknownElements(ignoreUnknownElements);
		jsonUnmarshaller.setComplexTypeGenerator(getComplexTypeGenerator());
		jsonUnmarshaller.setCamelCaseDashes(camelCaseDashes);
		jsonUnmarshaller.setCamelCaseUnderscores(camelCaseUnderscores);
		jsonUnmarshaller.setParseNumbers(parseNumbers);
		jsonUnmarshaller.setAllowRawNames(allowRaw);
		jsonUnmarshaller.setSetEmptyArrays(setEmptyArrays);
		jsonUnmarshaller.setIgnoreEmptyStrings(ignoreEmptyStrings);
		jsonUnmarshaller.setAddDynamicStringsOnly(addDynamicStringsOnly);
		jsonUnmarshaller.setAllowNilUnicode(allowNilCharacter);
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

	public boolean isAllowRaw() {
		return allowRaw;
	}

	public void setAllowRaw(boolean allowRaw) {
		this.allowRaw = allowRaw;
	}

	public boolean isPrettyPrint() {
		return prettyPrint;
	}

	public void setPrettyPrint(boolean prettyPrint) {
		this.prettyPrint = prettyPrint;
	}

	public boolean isSetEmptyArrays() {
		return setEmptyArrays;
	}

	public void setSetEmptyArrays(boolean setEmptyArrays) {
		this.setEmptyArrays = setEmptyArrays;
	}

	public boolean isIgnoreEmptyStrings() {
		return ignoreEmptyStrings;
	}

	public void setIgnoreEmptyStrings(boolean ignoreEmptyStrings) {
		this.ignoreEmptyStrings = ignoreEmptyStrings;
	}

	public boolean isExpandKeyValuePairs() {
		return expandKeyValuePairs;
	}

	public void setExpandKeyValuePairs(boolean expandKeyValuePairs) {
		this.expandKeyValuePairs = expandKeyValuePairs;
	}

	public boolean isUseAlias() {
		return useAlias;
	}

	public void setUseAlias(boolean useAlias) {
		this.useAlias = useAlias;
	}

	public boolean isIgnoreInconsistentTypes() {
		return ignoreInconsistentTypes;
	}

	public void setIgnoreInconsistentTypes(boolean ignoreInconsistentTypes) {
		this.ignoreInconsistentTypes = ignoreInconsistentTypes;
	}

	public boolean isIgnoreDynamicNames() {
		return ignoreDynamicNames;
	}

	public void setIgnoreDynamicNames(boolean ignoreDynamicNames) {
		this.ignoreDynamicNames = ignoreDynamicNames;
	}

	public boolean isMarshalNonExistingRequiredFields() {
		return marshalNonExistingRequiredFields;
	}

	public void setMarshalNonExistingRequiredFields(boolean marshalNonExistingRequiredFields) {
		this.marshalNonExistingRequiredFields = marshalNonExistingRequiredFields;
	}

	public boolean isAddDynamicStringsOnly() {
		return addDynamicStringsOnly;
	}

	public void setAddDynamicStringsOnly(boolean addDynamicStringsOnly) {
		this.addDynamicStringsOnly = addDynamicStringsOnly;
	}

	public boolean isAllowNilCharacter() {
		return allowNilCharacter;
	}

	public void setAllowNilCharacter(boolean allowNilCharacter) {
		this.allowNilCharacter = allowNilCharacter;
	}

}
