package ai.onnxruntime.genai;
public class Tokenizer implements AutoCloseable {
  public Tokenizer(Model m) throws GenAIException { throw new GenAIException("stub"); }
  public Sequences encode(String s) throws GenAIException { throw new GenAIException("stub"); }
  public String decode(int[] t) throws GenAIException { throw new GenAIException("stub"); }
  public TokenizerStream createStream() throws GenAIException { throw new GenAIException("stub"); }
  public void close() {}
}
