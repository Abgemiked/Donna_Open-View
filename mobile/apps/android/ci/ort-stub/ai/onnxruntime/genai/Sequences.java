package ai.onnxruntime.genai;
public final class Sequences implements AutoCloseable {
  public long numSequences() { return 0; }
  public int[] getSequence(long i) { return new int[0]; }
  public void close() {}
}
