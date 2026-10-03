// Pipe chunks are bytes; decode only when a caller explicitly needs text.
export function captureOutput(stream, echo) {
  const chunks = [];
  stream.on('data', chunk => {
    const bytes = Buffer.from(chunk);
    chunks.push(bytes);
    echo.write(bytes);
  });
  return () => Buffer.concat(chunks);
}
