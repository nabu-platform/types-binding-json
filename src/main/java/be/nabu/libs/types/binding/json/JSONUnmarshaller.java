package be.nabu.libs.types.binding.json;

import java.io.IOException;
import java.text.ParseException;

import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.Element;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.CharBuffer;
import be.nabu.utils.io.api.CountingReadableContainer;
import be.nabu.utils.io.api.DelimitedCharContainer;
import be.nabu.utils.io.api.ReadableContainer;

/**
 * Much easier if i can use the definition to parse...
 */
public class JSONUnmarshaller {
	
	private char [] single = new char[1];
	
	private static final int LOOK_AHEAD = 4096;
	private static final int MAX_SIZE = 1024*1024*10;
	
	private CharBuffer buffer = IOUtils.newCharBuffer(LOOK_AHEAD, true);
	
	public ComplexContent unmarshal(ReadableContainer<CharBuffer> reader, ComplexType type) throws IOException, ParseException {
		CountingReadableContainer<CharBuffer> readable = IOUtils.countReadable(reader);
		if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
			return null;
		}
		if (single[0] != '{') {
			throw new ParseException("Expecting a { to open the complex type", 0);
		}
		ComplexContent instance = type.newInstance();
		readField(readable, instance);
		return instance;
	}
	
	private void readField(CountingReadableContainer<CharBuffer> readable, ComplexContent content) throws ParseException, IOException {
		while(true) {
			if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
				throw new IOException("Could not read any data");
			}
			// first we expect an opening quote
			if (single[0] != '"') {
				throw new ParseException("Expecting a field", 0);
			}
			DelimitedCharContainer delimited = IOUtils.delimit(IOUtils.limitReadable(readable, LOOK_AHEAD), "[^\\\\]*\"$", 2);
			String fieldName = IOUtils.toString(delimited);
			if (!delimited.isDelimiterFound()) {
				throw new ParseException("Could not find end quote of tag name", 0);
			}
			// next we need to read a ":"
			if (ignoreWhitespace(readable).read(IOUtils.wrap(single, false)) != 1) {
				throw new IOException("Can not get the next character");
			}
			if (single[0] != ':') {
				throw new ParseException("Expecting a ':' after a field declaration", 0);
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
					else {
						unmarshalSingle(readable, fieldName, content, index++);
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
			}
			else {
				unmarshalSingle(readable, fieldName, content, null);
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

	private void unmarshalSingle(CountingReadableContainer<CharBuffer> readable, String fieldName, ComplexContent content, Integer index) throws IOException, ParseException {
		Object value = null;
		Element<?> element = content.getType().get(fieldName);
		switch(single[0]) {
			case '{':
				if (element == null) {
					throw new ParseException("The field " + fieldName + " is unexpected at this position", 0);
				}
				else if (!(element.getType() instanceof ComplexType)) {
					throw new ParseException("The field " + fieldName + " is not a complex type", 0);
				}
				ComplexContent child = ((ComplexType) element.getType()).newInstance();
				// recursively parse
				readField(readable, child);
				value = child;
			break;
			case '"':
				DelimitedCharContainer delimited = IOUtils.delimit(IOUtils.limitReadable(readable, MAX_SIZE), "[^\\\\]*\"$", 2);
				String fieldValue = IOUtils.toString(delimited);
				fieldValue = fieldValue.replaceAll("(?<!\\\\)\\\\n", "\n").replace("\\\\", "\\").replace("\\\"", "\"");
				if (!delimited.isDelimiterFound()) {
					throw new ParseException("Could not find the closing quote of the string value", 0);
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
				// must be a number then...
				else {
					delimited = IOUtils.delimit(readable, "[^0-9.E]+", 1);
					value = single[0] + IOUtils.toString(delimited);
					// the number is delimited by something that is not a number and this is very likely a valid part of the json syntax
					// push this back onto the buffer so it is taken into account for the next parse
					buffer.write(IOUtils.wrap(delimited.getMatchedDelimiter()));
				}
		}
		boolean isList = element.getType().isList(element.getProperties());
		if (index != null) {
			if (!isList) {
				throw new ParseException("The element " + fieldName + " is an array in the json but not a list", 0);
			}
			content.set(fieldName + "[" + index + "]", value);
		}
		else if (isList) {
			throw new ParseException("The element " + fieldName + " is a list but not an array in the json", 0);
		}
		else {
			content.set(fieldName, value);
		}
	}
	
	@SuppressWarnings("unchecked")
	private ReadableContainer<CharBuffer> ignoreWhitespace(ReadableContainer<CharBuffer> parent) {
		return IOUtils.ignore(IOUtils.chain(false, buffer, parent), ' ', '\t', '\n', '\r');
	}
}
