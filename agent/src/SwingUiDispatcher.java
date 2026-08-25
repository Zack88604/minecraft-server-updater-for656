import javax.swing.SwingUtilities;

/**
 * {@link UiDispatcher} that schedules actions on Swing's Event Dispatch Thread
 * via {@link SwingUtilities#invokeLater}.
 */
final class SwingUiDispatcher implements UiDispatcher {

    @Override
    public void invoke(Runnable action) {
        SwingUtilities.invokeLater(action);
    }
}
