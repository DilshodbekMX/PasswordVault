package io.github.dilshodbekmx.pvlab.window;

/**
 * [StateProbe] on this app's own [ControlActivity], whose platform text fields save their text: the
 * control of state.sh's method (the probe must find a text that is in a saved Bundle).
 *
 *   adb shell am instrument io.github.dilshodbekmx.pvlab.window/.StateProbeSelf
 */
public class StateProbeSelf extends StateProbe {
}
