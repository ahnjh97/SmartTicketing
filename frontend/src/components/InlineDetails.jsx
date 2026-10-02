import { Fragment } from 'react';
import styles from './InlineDetails.module.css';

export default function InlineDetails({ items }) {
    return <span className={styles.details}>{items.map((item, index) =>
        <Fragment key={index}>{index > 0 && ' '}<span>{item}</span></Fragment>
    )}</span>;
}
