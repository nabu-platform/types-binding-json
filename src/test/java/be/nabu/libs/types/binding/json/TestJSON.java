package be.nabu.libs.types.binding.json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.Arrays;

import junit.framework.TestCase;
import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.java.BeanInstance;
import be.nabu.libs.types.java.BeanType;

public class TestJSON extends TestCase {
	public void testJSON() throws IOException, ParseException {
		InputStream input = TestJSON.class.getClassLoader().getResourceAsStream("test.json");
		JSONBinding binding = new JSONBinding(new BeanType<Company>(Company.class));
		try {
			Window window = new Window("company/employees", 3, 3);
			Company result = TypeUtils.getAsBean(binding.unmarshal(input, new Window[] { window }), Company.class);
			assertEquals("Nabu", result.getName());
			assertEquals("Organizational", result.getUnit());
			assertEquals("Nabu HQ", result.getAddress());
			assertEquals("BE666-66-66", result.getBillingNumber());
			assertEquals(24, result.getEmployees().size());
			assertEquals("John1", result.getEmployees().get(1).getFirstName());
			
			assertEquals(new Integer(31), result.getEmployees().get(0).getAge());
			assertEquals(new Integer(57), result.getEmployees().get(1).getAge());
			assertEquals(new Integer(60), result.getEmployees().get(10).getAge());
			assertEquals(new Integer(44), result.getEmployees().get(14).getAge());
			assertEquals(new Integer(47), result.getEmployees().get(19).getAge());

			assertEquals(new Integer(31), result.getEmployees().get(0).getAge());
			assertEquals(new Integer(57), result.getEmployees().get(1).getAge());
		}
		finally {
			input.close();
		}
	}

	@SuppressWarnings("rawtypes")
	public void testRootArrays() throws IOException, ParseException {
		JSONBinding binding = new JSONBinding(new BeanType<Listable>(Listable.class));
		binding.setIgnoreRootIfArrayWrapper(true);
		Listable listable = new Listable();
		listable.setTests(Arrays.asList(new Listable.TestClass("test1"), new Listable.TestClass("test2")));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		binding.marshal(output, new BeanInstance(listable));
		String content = new String(output.toByteArray());
		assertEquals("[{\"name\": \"test1\"}, {\"name\": \"test2\"}]", content);
		ComplexContent unmarshal = binding.unmarshal(new ByteArrayInputStream(output.toByteArray()), new Window[0]);
		Listable bean = TypeUtils.getAsBean(unmarshal, Listable.class);
		assertEquals(listable.getTests(), bean.getTests());
	}
}
