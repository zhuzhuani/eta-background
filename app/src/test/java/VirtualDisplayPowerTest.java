import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualDisplayPowerTest {
    @Test public void screenPowerNeverTargetsThePhoneOrAnUnverifiedDisplayGroup() {
        assertTrue(VirtualDisplayPower.isIsolated(7, 3, 0));
        assertFalse(VirtualDisplayPower.isIsolated(0, 3, 0));
        assertFalse(VirtualDisplayPower.isIsolated(7, 0, 0));
        assertFalse(VirtualDisplayPower.isIsolated(7, -1, 0));
        assertFalse(VirtualDisplayPower.isIsolated(7, 3, -1));
    }
}
