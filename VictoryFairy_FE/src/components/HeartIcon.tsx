/** 하트 모양(24 x 24). 아이콘 에셋에 하트가 없어 직접 그린다. */
export default function HeartIcon({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 21s-7.5-4.6-9.6-9.3C1 8.6 2.9 5 6.4 5c2.1 0 3.6 1.1 4.6 2.6l1 1.4 1-1.4C14 6.1 15.5 5 17.6 5 21.1 5 23 8.6 21.6 11.7 19.5 16.4 12 21 12 21z" />
    </svg>
  );
}
