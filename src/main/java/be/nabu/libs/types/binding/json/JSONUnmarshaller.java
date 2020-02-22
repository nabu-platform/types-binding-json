package be.nabu.libs.types.binding.json;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import be.nabu.libs.property.api.Value;
import be.nabu.libs.resources.URIUtils;
import be.nabu.libs.types.BaseTypeInstance;
import be.nabu.libs.types.CollectionHandlerFactory;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.TypeConverterFactory;
import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.CollectionHandlerProvider;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedSimpleType;
import be.nabu.libs.types.api.Element;
import be.nabu.libs.types.api.KeyValuePair;
import be.nabu.libs.types.api.ModifiableComplexType;
import be.nabu.libs.types.api.ModifiableComplexTypeGenerator;
import be.nabu.libs.types.api.TypeInstance;
import be.nabu.libs.types.base.ComplexElementImpl;
import be.nabu.libs.types.base.SimpleElementImpl;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.binding.BindingUtils;
import be.nabu.libs.types.java.BeanResolver;
import be.nabu.libs.types.java.BeanType;
import be.nabu.libs.types.properties.AliasProperty;
import be.nabu.libs.types.properties.MaxOccursProperty;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.CharBuffer;
import be.nabu.utils.io.api.CountingReadableContainer;
import be.nabu.utils.io.api.DelimitedCharContainer;
import be.nabu.utils.io.api.ReadableContainer;

import java.util.ArrayList;

/**
 * Much easier if i can use the definition to parse...
 */
public class JSONUnmarshaller {
	
	private char [] single = new char[1];
	
	private static final int LOOK_AHEAD = 4096;
	private static final int MAX_SIZE = 1024*1024*10;
	
	// do best effort if we can
	private boolean lenient = true;
	
	private CharBuffer buffer = IOUtils.newCharBuffer(LOOK_AHEAD, true);
	
	private boolean allowDynamicElements, addDynamicElementDefinitions, ignoreUnknownElements, camelCaseDashes, camelCaseUnderscores, normalize = true, setEmptyArrays;
	private boolean allowRawNames;
	private boolean ignoreEmptyStrings;
	
	private ModifiableComplexTypeGenerator complexTypeGenerator;
	
	private boolean ignoreRootIfArrayWrapper = false;
	private boolean strict;
	private boolean decodeUnicode = true;
	private boolean parseNumbers = false;
	
	@SuppressWarnings("unchecked")
	public ComplexContent unmarshal(ReadableContainer<CharBuffer> reader, ComplexType type) throws IOException, ParseException {
		CountingReadableContainer<CharBuffer> readable = IOUtils.countReadable(reader);
		if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
			return null;
		}
		if (single[0] == '[' && ignoreRootIfArrayWrapper) {
			Collection<Element<?>> allChildren = TypeUtils.getAllChildren(type);
			if (allChildren.size() == 0 && allowDynamicElements && complexTypeGenerator != null) {
				Element<?> element = new ComplexElementImpl("array", complexTypeGenerator.newComplexType(), type, new ValueImpl<Integer>(MaxOccursProperty.getInstance(), 0));
				if (addDynamicElementDefinitions && type instanceof ModifiableComplexType) {
					((ModifiableComplexType) type).add(element);
				}
				allChildren = new ArrayList<Element<?>>(Arrays.asList(element));
			}
			if (allChildren.size() == 1) {
				Element<?> element = allChildren.iterator().next();
				if (element.getType().isList(element.getProperties())) {
					ComplexContent instance = type.newInstance();
					int index = 0;
					while (true) {
						if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
							throw new IOException("Can not get the next character");
						}
						// done
						if (single[0] == ']') {
							break;
						}
						else {
							unmarshalSingle(readable, element.getName(), instance, index++, false, element.getName());
						}
						if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
							throw new IOException("Can not get the next character");
						}
						if (single[0] == ']') {
							break;
						}
						// next
						else if (single[0] != ',') {
							throw new ParseException("Expecting a ',' to indicate the next part of the array or a ']' to indicate the end", 0);
						}
					}
					// no elements
					if (index == 0 && setEmptyArrays) {
						instance.set(element.getName(), new ArrayList<Object>());
					}
					return instance;
				}
			}
		}
		// it is possible to send complex types (at the root) without a {} around it, e.g. facebook on oauth2 token request sends something like:
		// access_token=<token>
		else if (single[0] != '{') {
			if (strict) {
				throw new ParseException("Expecting a { to open the complex type", 0);
			}
			else {
				readable = IOUtils.countReadable(IOUtils.chain(false, IOUtils.wrap(single, true), readable));
			}
		}
		ComplexContent instance = type.newInstance();
		readField(readable, instance, false);
		return instance;
	}
	
	@SuppressWarnings("unchecked")
	private void readField(CountingReadableContainer<CharBuffer> readable, ComplexContent content, boolean inDynamic) throws ParseException, IOException {
		while(true) {
			if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
				throw new IOException("Could not read any data");
			}
			DelimitedCharContainer delimited;
			// it could be immediately closed
			if (single[0] == '}') {
				break;
			}
			// first we expect an opening quote
			else if (single[0] == '"') {
				delimited = IOUtils.delimit(IOUtils.limitReadable(readable, LOOK_AHEAD), "[^\\\\]*\"$", 2);
			}
			else if (strict) {
				throw new ParseException("Expecting a field", 0);
			}
			// this is to support "invalid" json fields that do not have quotes around them, e.g. facebook sends back _invalid_ json without quotes around the field names
			else {
				delimited = IOUtils.delimit(IOUtils.limitReadable(IOUtils.chain(false, IOUtils.wrap(single, true), readable), LOOK_AHEAD), "[\\s]*:$", 2);
			}
			String fieldName = IOUtils.toString(delimited);
			String rawFieldName = fieldName;
			if (!allowRawNames) {
				fieldName = preprocess(encodeFieldName(unescape(fieldName)));
			}
			if (!delimited.isDelimiterFound()) {
				throw new ParseException("Could not find delimiter of tag name: " + fieldName, 0);
			}
			if (strict || !":".equals(delimited.getMatchedDelimiter())) {
				// next we need to read a ":"
				if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
					throw new IOException("Can not get the next character");
				}
				if (single[0] != ':') {
					throw new ParseException("Expecting a ':' after a field declaration", 0);
				}
			}
			// skip the whitespace and read one character, this char will determine what the field is
			if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
				throw new IOException("Can not get the next character");
			}
			// we are doing an array
			if (single[0] == '[') {
				int index = 0;
				while (true) {
					if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
						throw new IOException("Can not get the next character");
					}
					// empty
					if (single[0] == ']') {
						break;
					}
					// matrix, only 2 deep atm...
					else if (single[0] == '[') {
						while(true) {
							if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
								throw new IOException("Can not get the next character");
							}
							// empty
							if (single[0] == ']') {
								break;
							}
							else if (single[0] == '[') {
								throw new ParseException("Matrices are only supported 2 deep currently", 0);
							}
							else {
								unmarshalSingle(readable, fieldName, content, index++, inDynamic, rawFieldName);
							}
							if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
								throw new IOException("Can not get the next character");
							}
							if (single[0] == ']') {
								break;
							}
							// next
							else if (single[0] != ',') {
								throw new ParseException("Expecting a ',' to indicate the next part of the array or a ']' to indicate the end for field: " + rawFieldName, 0);
							}
						}
					}
					else {
						unmarshalSingle(readable, fieldName, content, index++, inDynamic, rawFieldName);
					}
					if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
						throw new IOException("Can not get the next character");
					}
					if (single[0] == ']') {
						break;
					}
					// next
					else if (single[0] != ',') {
						throw new ParseException("Expecting a ',' to indicate the next part of the array or a ']' to indicate the end for field: " + rawFieldName, 0);
					}
				}
				// no elements
				if (index == 0 && setEmptyArrays && content != null) {
					Element<?> element = getRawChild(content.getType(), rawFieldName);
					if (element == null) {
						element = content.getType().get(fieldName);
					}
					if (element != null) {
						content.set(element.getName(), new ArrayList<Object>());
					}
					// if we allow dynamic elements, set an empty array for the field
					else if (allowDynamicElements) {
						content.set(allowRawNames ? rawFieldName : fieldName, new ArrayList<Object>());
					}
				}
			}
			else {
				unmarshalSingle(readable, fieldName, content, null, inDynamic, rawFieldName);
			}
			// it has to be a ',' or a '}'
			if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
				throw new IOException("Can not get the next character");
			}
			if (single[0] == '}') {
				break;
			}
			else if (single[0] != ',') {
				throw new ParseException("Expecting a ',' at this position, not " + single[0], (int) readable.getReadTotal());
			}
		}
	}
	
	/**
	 * In JSON the field names can be _anything_, we need to encode it in order to pass through stuff like the ParsedPath etc
	 */
	private String encodeFieldName(String name) {
		return name == null ? name : URIUtils.encodeURIComponent(name);
	}
	
	private String preprocess(String name) {
		if (normalize) {
			while (name.startsWith("-") || name.startsWith("_")) {
				name = name.substring(1);
			}
		}
		if (camelCaseDashes) {
			name = BindingUtils.camelCaseCharacter(name, '-');
		}
		if (camelCaseUnderscores) {
			name = BindingUtils.camelCaseCharacter(name, '_');			
		}
		return name;
	}
	
	private Element<?> getRawChild(ComplexType type, String name) {
		for (Element<?> child : TypeUtils.getAllChildren(type)) {
			if (child.getName().equals(name)) {
				return child;
			}
			Value<String> property = child.getProperty(AliasProperty.getInstance());
			if (property != null && property.getValue() != null && property.getValue().equals(name)) {
				return child;
			}
		}
		return null;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void unmarshalSingle(CountingReadableContainer<CharBuffer> readable, String fieldName, ComplexContent content, Integer index, boolean inDynamic, String rawFieldName) throws IOException, ParseException {
		Object value = null;
		
		Element<?> element = null;
		
		if (content != null) {
			element = getRawChild(content.getType(), rawFieldName);
			if (element == null) {
				element = content.getType().get(fieldName);
			}
			// check if it exists as an attribute
			// this ensures compatibility with XML structures where fields may be expressed as attributes
			if (element == null) {
				element = getRawChild(content.getType(), "@" + rawFieldName);
			}
			if (element == null) {
				element = content.getType().get("@" + fieldName);
			}
		}
		
		// could be working with aliases
		if (element != null) {
			fieldName = element.getName();
		}
		
		boolean dynamicToKeyValue = false;
		switch(single[0]) {
			case '{':
				// if we have an Object, we want dynamic behavior to kick in, an object can't really do much
				// if we allow dynamic elements, create one
				if (allowDynamicElements && complexTypeGenerator != null && (element == null || (element.getType() instanceof BeanType && ((BeanType<?>) element.getType()).getBeanClass().equals(Object.class)))) {
					// if we get here the element is either null or a java.lang.Object
					boolean isObject = element != null;
					if (index == null) {
						element = new ComplexElementImpl(fieldName, complexTypeGenerator.newComplexType(), content.getType());
					}
					else {
						element = new ComplexElementImpl(fieldName, complexTypeGenerator.newComplexType(), content.getType(), new ValueImpl<Integer>(MaxOccursProperty.getInstance(), 0));
					}
					// if we have a raw field name, add it to alias
					if (!rawFieldName.equals(fieldName)) {
						element.setProperty(new ValueImpl<String>(AliasProperty.getInstance(), rawFieldName));
					}
					if ((addDynamicElementDefinitions || inDynamic) && content.getType() instanceof ModifiableComplexType) {
						((ModifiableComplexType) content.getType()).add(element);
					}
					else if (!inDynamic) {
						// only set this to true if we didn't start out with a java.lang.Object
						// if we did start out that way, we can simply reuse the existing field to put the dynamic stuff in
						dynamicToKeyValue = !isObject;
					}
					inDynamic = true;
				}
				if (!ignoreUnknownElements && element == null) {
					throw new ParseException("The field " + fieldName + " is unexpected at this position", 0);
				}
				else if (element != null && !(element.getType() instanceof ComplexType)) {
					throw new ParseException("The field " + fieldName + " is not a complex type", 0);
				}
				ComplexContent child = element == null ? null : ((ComplexType) element.getType()).newInstance();
				// recursively parse
				readField(readable, child, inDynamic);
				value = child;
			break;
			case '"':
				DelimitedCharContainer delimited = IOUtils.delimit(IOUtils.limitReadable(readable, MAX_SIZE), "[^\\\\]*\"$", 2);
				String fieldValue = IOUtils.toString(delimited);
				if (!delimited.isDelimiterFound()) {
					throw new ParseException("Could not find the closing quote of the string value", 0);
				}
				fieldValue = unescape(fieldValue);
				if (decodeUnicode) {
					Pattern pattern = Pattern.compile("\\\\u([0-9a-f]{4})");
					Matcher matcher = pattern.matcher(fieldValue);
					while(matcher.find()) {
						fieldValue = fieldValue.replace(matcher.group(), new String(new char [] { (char) Integer.parseInt(matcher.group(1), 16) }));
					}
				}
				value = fieldValue;
			break;
			// has to be a native type (number or boolean)
			default:
				// should spell true
				if (single[0] == 't' || single[0] == 'T') {
					String rest = IOUtils.toString(IOUtils.limitReadable(readable, 3));
					if (!rest.equalsIgnoreCase("rue")) {
						throw new ParseException("The value " + single[0] + rest + " is not valid", 0);
					}
					value = true;
				}
				// should spell false
				else if (single[0] == 'f' || single[0] == 'F') {
					String rest = IOUtils.toString(IOUtils.limitReadable(readable, 4));
					if (!rest.equalsIgnoreCase("alse")) {
						throw new ParseException("The value " + single[0] + rest + " is not valid", 0);
					}
					value = false;
				}
				// should spell "null"
				else if (single[0] == 'n' || single[0] == 'N') {
					String rest = IOUtils.toString(IOUtils.limitReadable(readable, 3));
					if (!rest.equalsIgnoreCase("ull")) {
						throw new ParseException("The value " + single[0] + rest + " is not valid", 0);
					}
					value = null;
				}
				// must be a number then...
				else {
					delimited = IOUtils.delimit(readable, "[^0-9.E+-]+", 1);
					value = single[0] + IOUtils.toString(delimited);
					if (parseNumbers) {
						try {
							if (((String) value).contains(".")) {
								value = Double.parseDouble((String) value);
							}
							else {
								value = Long.parseLong((String) value);
							}
						}
						catch (Exception e) {
							try {
								if (((String) value).contains(".")) {
									value = new BigDecimal((String) value);
								}
								else {
									value = new BigInteger((String) value);
								}
							}
							catch (Exception e1) {
								// just leave it as string
							}
						}
					}
					// the number is delimited by something that is not a number and this is very likely a valid part of the json syntax
					// push this back onto the buffer so it is taken into account for the next parse
					buffer.write(IOUtils.wrap(delimited.getMatchedDelimiter()));
				}
		}
		if (ignoreEmptyStrings && value instanceof String && ((String) value).isEmpty()) {
			value = null;
		}
		if (value != null) {
			boolean isKeyValuePair = false;
			// if there is no element, let's see if you have a catch all keyvaluepair list
			if (content != null && (element == null || dynamicToKeyValue)) {
				TypeInstance keyValueInstance = new BaseTypeInstance(BeanResolver.getInstance().resolve(KeyValuePair.class));
				for (Element<?> child : TypeUtils.getAllChildren(content.getType())) {
					// apart from it being a list, other child properties don't matter so strip them for subset comparison 
					if (child.getType().isList(child.getProperties()) && TypeUtils.isSubset(new BaseTypeInstance(child.getType()), keyValueInstance)) {
						element = child;
						isKeyValuePair = true;
						break;
					}
				}
			}
			// must be a simple value
			if ((!ignoreUnknownElements || inDynamic) && allowDynamicElements && element == null) {
				DefinedSimpleType<? extends Object> wrap = SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(value.getClass());
				if (wrap == null) {
					throw new ParseException("Can not dynamically wrap: " + value, 0);
				}
				else if (index == null) {
					element = new SimpleElementImpl(fieldName, wrap, content.getType());
				}
				else {
					element = new SimpleElementImpl(fieldName, wrap, content.getType(), new ValueImpl<Integer>(MaxOccursProperty.getInstance(), 0));
				}
				// if we have a raw field name, add it to alias
				if (!rawFieldName.equals(fieldName)) {
					element.setProperty(new ValueImpl<String>(AliasProperty.getInstance(), rawFieldName));
				}
				if ((addDynamicElementDefinitions || inDynamic) && content.getType() instanceof ModifiableComplexType) {
					((ModifiableComplexType) content.getType()).add(element);
				}
			}
			if (!ignoreUnknownElements && element == null) {
				throw new ParseException("The field " + rawFieldName + " is unexpected at this position", 0);
			}
			if (content != null && element != null) {
				if (isKeyValuePair) {
					String key = rawFieldName;
					if (index != null) {
						key += "[" + index + "]";
					}
					if (value instanceof ComplexContent) {
						List<Object> keyValues = new ArrayList<Object>();
						toProperties((ComplexContent) value, keyValues, null, (ComplexType) element.getType());
						Object object = content.get(element.getName());
						int keyValuePairIndex = 0;
						if (object != null) {
							CollectionHandlerProvider handler = CollectionHandlerFactory.getInstance().getHandler().getHandler(object.getClass());
							if (handler == null) {
								throw new IllegalStateException("Expecting a collection of key value pairs");
							}
							keyValuePairIndex = handler.getIndexes(object).size();
						}
						for (Object single : keyValues) {
							content.set(element.getName() + "[" + keyValuePairIndex++ + "]", single);
						}
					}
					else {
						ComplexContent keyValuePair = ((ComplexType) element.getType()).newInstance();
						keyValuePair.set("key", key);
						keyValuePair.set("value", value);
						Object object = content.get(element.getName());
						int keyValuePairIndex = 0;
						if (object != null) {
							CollectionHandlerProvider handler = CollectionHandlerFactory.getInstance().getHandler().getHandler(object.getClass());
							if (handler == null) {
								throw new IllegalStateException("Expecting a collection of key value pairs");
							}
							keyValuePairIndex = handler.getIndexes(object).size();
						}
						content.set(element.getName() + "[" + keyValuePairIndex + "]", keyValuePair);
					}
				}
				else if (dynamicToKeyValue) {
					if (!ignoreUnknownElements) {
						throw new ParseException("Created a dynamic type for field '" + rawFieldName + "' but found no key value pair to map it to", 0);
					}
				}
				else {
					boolean isList = element.getType().isList(element.getProperties());
					if (index != null) {
						if (!isList) {
							// if we have more than one entry in the list, it will fail soon
							if (lenient && index == 0) {
								content.set(fieldName, value);
							}
							else {
								throw new ParseException("The element " + fieldName + " is an array in the json but not a list", 0);
							}
						}
						else {
							content.set(fieldName + "[" + index + "]", value);
						}
					}
					else if (isList) {
						// if we are doing lenient parsing, we have a target list and a singular value, we just put it in the first element
						if (lenient) {
							content.set(fieldName + "[0]", value);	
						}
						else {
							throw new ParseException("The element " + fieldName + " is a list but not an array in the json", 0);
						}
					}
					else {
						content.set(fieldName, value);
					}
				}
			}
		}
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void toProperties(ComplexContent content, List<Object> properties, String path, ComplexType keyValuePairDefinition) {
		for (Element<?> child : TypeUtils.getAllChildren(content.getType())) {
			String childPath = path == null ? child.getName() : path + "." + child.getName();
			java.lang.Object value = content.get(child.getName());
			if (value != null) {
				CollectionHandlerProvider collectionHandler = CollectionHandlerFactory.getInstance().getHandler().getHandler(value.getClass());
				if (collectionHandler != null) {
					for (java.lang.Object index : collectionHandler.getIndexes(value)) {
						java.lang.Object singleValue = collectionHandler.get(value, index);
						if (singleValue != null) {
							String singlePath = childPath;
							if (index instanceof Number) {
								singlePath += "[" + index + "]";
							}
							else {
								singlePath += "[\"" + index + "\"]";
							}
							singleToProperties(child, singleValue, properties, singlePath, keyValuePairDefinition);
						}
					}
				}
				else {
					singleToProperties(child, value, properties, childPath, keyValuePairDefinition);
				}
			}
		}
	}
	
	private static void singleToProperties(Element<?> child, java.lang.Object value, List<Object> properties, String childPath, ComplexType keyValuePairDefinition) {
		if (value instanceof ComplexContent) {
			toProperties((ComplexContent) value, properties, childPath, keyValuePairDefinition);
		}
		else {
			ComplexContent newInstance = keyValuePairDefinition.newInstance();
			newInstance.set("key", childPath);
			newInstance.set("value", value instanceof String 
				? (String) value
				: (java.lang.String) TypeConverterFactory.getInstance().getConverter().convert(value, child, new BaseTypeInstance(new be.nabu.libs.types.simple.String())));
			properties.add(newInstance);
		}
	}
	
	public static String unescape(String value) {
		return value == null ? null : value.replaceAll("(?<!\\\\)\\\\n", "\n").replaceAll("(?<!\\\\)\\\\t", "\t").replace("\\\\", "\\").replace("\\\"", "\"").replace("\\/", "/");
	}
	
	@SuppressWarnings("unchecked")
	private ReadableContainer<CharBuffer> ignoreWhitespace(ReadableContainer<CharBuffer> parent) {
		return IOUtils.ignore(IOUtils.chain(false, buffer, parent), ' ', '\t', '\n', '\r');
	}

	public ModifiableComplexTypeGenerator getComplexTypeGenerator() {
		return complexTypeGenerator;
	}

	public void setComplexTypeGenerator(ModifiableComplexTypeGenerator complexTypeGenerator) {
		this.complexTypeGenerator = complexTypeGenerator;
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

	public boolean isAllowRawNames() {
		return allowRawNames;
	}

	public void setAllowRawNames(boolean allowRawNames) {
		this.allowRawNames = allowRawNames;
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

}
