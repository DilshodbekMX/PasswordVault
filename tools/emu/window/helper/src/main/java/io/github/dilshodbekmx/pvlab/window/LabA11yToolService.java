package io.github.dilshodbekmx.pvlab.window;

/**
 * [LabA11yService] declared as an accessibility tool (res/xml/lab_a11y_tool.xml), as a screen reader
 * is: what the app shows to a service that Android 14 and newer do not keep out. Android 9 does not
 * know the difference, so there both services read the same.
 */
public class LabA11yToolService extends LabA11yService {
    @Override
    String kind() {
        return "tool";
    }
}
