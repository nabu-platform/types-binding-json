package be.nabu.libs.types.binding.json;

import java.util.List;

public class Listable {

	private List<TestClass> tests;
	
	public List<TestClass> getTests() {
		return tests;
	}

	public void setTests(List<TestClass> tests) {
		this.tests = tests;
	}

	public static class TestClass {
		private String name;

		public TestClass() {
			// auto construct
		}
		
		public TestClass(String name) {
			this.name = name;
		}
		
		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
		
		public boolean equals(Object object) {
			return object instanceof TestClass && ((TestClass) object).name.equals(name);
		}
		
		public int hashCode() {
			return name == null ? super.hashCode() : name.hashCode();
		}
	}
}
