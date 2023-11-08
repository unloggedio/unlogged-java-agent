package io.unlogged.testdata;

/**
 * This simple class caused a VerifyError (Issue #8)
 */
public class DivideClass {

	private int x = 0;
	
	public void c(int row, boolean[] list) {
		int r = row / 2;
		if (x != 0)
			list[0] = true;
	}

}
