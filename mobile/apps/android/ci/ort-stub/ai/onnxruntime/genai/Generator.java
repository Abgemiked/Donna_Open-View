package ai.onnxruntime.genai;
public final class Generator implements AutoCloseable {
  public Generator(Model m, GeneratorParams p) throws GenAIException { throw new GenAIException("stub"); }
  public boolean isDone() { return true; }
  public void appendTokens(int[] t) throws GenAIException {}
  public void appendTokenSequences(Sequences s) throws GenAIException {}
  public void generateNextToken() throws GenAIException {}
  public int[] getSequence(long i) throws GenAIException { return new int[0]; }
  public int getLastTokenInSequence(long i) throws GenAIException { return 0; }
  public void close() {}
}
