package be.nabu.libs.types.binding.json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.ParseException;
import java.util.Map;

import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.java.BeanResolver;
import junit.framework.TestCase;

public class TestMap extends TestCase {
	public static interface TestWithMap {
		public String getName();
		public Map<String, String> getConfiguration();
	}
	
	public void testMapConfiguration() throws IOException, ParseException {
		String json = "{\"configuration\": {\n"
				+ "		  \"apiKey\": \"testcase\"\n"
				+ "		},\n"
				+ "		\"name\": \"something\""
				+ "}";
		JSONBinding jsonBinding = new JSONBinding((ComplexType) BeanResolver.getInstance().resolve(TestWithMap.class));
		jsonBinding.setAllowDynamicElements(true);
		jsonBinding.setEnableMapSupport(true);
		TestWithMap bean = TypeUtils.getAsBean(jsonBinding.unmarshal(new ByteArrayInputStream(json.getBytes()), new Window[0]), TestWithMap.class);
		System.out.println(bean.getConfiguration().keySet());
		assertEquals("testcase", bean.getConfiguration().get("apiKey"));
	}
}
