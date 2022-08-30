package be.nabu.libs.types.binding.json;

import junit.framework.TestCase;

public class TestEscape extends TestCase {
	public void testEscape() {
		String content = "test\nth/is\\n\u0006";
		String escape = JSONBinding.escape(content, false, false);
		assertEquals("test\\nth\\/is\\\\n\\u0006", escape);
		String unescaped = JSONUnmarshaller.unescapeFull(escape, false);
		assertEquals(content, unescaped);
	}
	public void testEscape2() {
		String content = "test\nth/is\\n\u0006e";
		String escape = JSONBinding.escape(content, false, false);
		assertEquals("test\\nth\\/is\\\\n\\u0006e", escape);
		String unescaped = JSONUnmarshaller.unescapeFull(escape, false);
		assertEquals(content, unescaped);
	}
}
