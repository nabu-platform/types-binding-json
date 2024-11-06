/*
* Copyright (C) 2014 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

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
