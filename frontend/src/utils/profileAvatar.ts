/** Static images are cropped to a square and compressed locally; GIFs preserve animation. */
export async function prepareAvatar(file: File): Promise<Blob> {
  if (!['image/jpeg', 'image/png', 'image/webp', 'image/gif'].includes(file.type)) throw new Error('请选择 JPG、PNG、WebP 或 GIF 图片');
  if (file.size > 5 * 1024 * 1024) throw new Error('原始图片不能超过 5MB');
  if (file.type === 'image/gif') {
    if (file.size > 20 * 1024) throw new Error('动态 GIF 需小于 20KB；静态图片会自动压缩');
    return file;
  }
  const bitmap = await createImageBitmap(file);
  try {
    if (bitmap.width * bitmap.height > 40_000_000) throw new Error('图片尺寸过大，请选择较小的图片');
    const side = Math.min(bitmap.width, bitmap.height);
    for (const size of [256, 192, 128]) {
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = size;
      const context = canvas.getContext('2d');
      if (!context) throw new Error('浏览器无法处理图片');
      context.fillStyle = '#f8f7f3';
      context.fillRect(0, 0, size, size);
      context.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, size, size);
      for (const quality of [0.88, 0.72, 0.55, 0.38]) {
        const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', quality));
        if (blob && blob.size <= 20 * 1024) return blob;
      }
    }
    throw new Error('图片压缩失败，请尝试其他图片');
  } finally { bitmap.close(); }
}
